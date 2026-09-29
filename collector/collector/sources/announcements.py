"""MS-21 P2 Task 1 公告双源：巨潮 hisAnnouncement 主源 + 东财公告流降级源。

URL/字段/分页语义全部钉在探测报告实测（09-调研报告/2026-09-29-MS20-数据源探测报告.md
§3 巨潮 + §4 东财公告流——§4 响应样本与字段映射为 2026-09-29 P2 补测）：

- 巨潮 ``CninfoAnnouncementSource``（DB source 列值 ``cninfo``）：POST form-encoded
  ``hisAnnouncement/query``，``announcementId`` 幂等；``announcementTime`` epoch ms
  恒为披露日北京时间零点（日粒度）；PDF = ``static.cninfo.com.cn/`` + adjunctUrl（§3.3
  实测成立）；``announcementType`` 分类码链原样入 ``ann_type_source``。个股查询 orgId
  不可推导（错/缺静默 0 条）——经 topSearch ``keyBoardList``（含 plate=sse/szse/bj）
  惰性解析 + 进程内缓存（orgId 长期稳定）；多股 ``;`` 分隔实测不被支持，逐股查询。
- 东财 ``EastmoneyAnnouncementSource``（DB source 列值 ``eastmoney_ann``）：GET
  ``np-anotice-stock …/api/security/ann``，``art_code`` 幂等；``stock_list`` 多股逗号
  分隔一次流、缺省即全市场流（ann_type=A 覆盖沪深北）；``display_time`` 毫秒精度
  （第三冒号后毫秒段，剥离再解析）；``notice_date`` 日粒度且晚间披露进位次日（与巨潮
  announcementTime 同口径）；PDF 直链 ``pdf.dfcfw.com/pdf/H2_{art_code}_1.pdf``
  （P2 补测 HEAD 200 验证，免 content API 二次请求）。

**覆盖口径（控制器裁定）**：关注集标的（``params['stocks']``——list/逗号串，或
``params['stocks_env']`` 指定 env 变量名，缺省回落 ``ANN_STOCKS_ENV``；collector 不查
业务表）**全量采集**（所有栏目，逐股无 category 查询，条目 major=false）；全市场仅
``major=true`` 条目——**major 只按探测报告 §3.4 栏目映射直判的 6 类专属栏目**
（定期报告/业绩预告/业绩快报/定增配股/股权激励/退市风险，``ANNOUNCEMENT_MAJOR_COLUMNS``
并集 10 category × 3 市场）；增持/减持/回购/关联交易 4 类无专属栏目**不做全市场扫描**，
仅在关注集标的全量中由 LLM 标签判定（P2 Task 5）。关注股的 6 类条目已由全市场栏目
扫描以 major=true 采得，run 级 seen 去重不重复输出。

增量策略：显式 ``params['start']/['end']``（YYYY-MM-DD/紧凑式）给出查询窗口（巨潮映射
``seDate``、东财作翻页下界），缺省回看 ``ANN_LOOKBACK_DAYS`` 天（覆盖周五晚→周一早
周末缺口，任务 MON-FRI 双跑）；增量轮次按**整页无新增**截断——巨潮全市场栏目查询
announcementTime 日粒度严格倒序但**同日内 announcementId 乱序**、东财 display_time
毫秒位秒内乱序（均 P2 补测实测），新闻源「首见已存即停」在这里会漏同日晚披露条目，
整页判定配合日边界严格倒序才安全；东财页间实测有重叠（活跃时段头部插入），run 级
seen 去重。已存集合按本源 source 过滤（业务键 ``(source, external_id)``，双源 id 空间
不相交）。

异常语义：请求级失败（网络/超时/非 200/JSON 解析）重试 ``ANN_RETRY_ATTEMPTS`` 次后抛
``SourceError`` 让 selector 换源（**不自吞**）；响应 200 但预期结构缺失（上游漂移）直接
``SourceError``；单条解析失败丢弃并记 ``last_warnings``（不改终态）；topSearch 解析
失败软降级——跳过该标的（其 6 类直判条目仍由全市场扫描覆盖）。
"""

import datetime as dt
import json
import logging
import os
import time
from urllib.parse import urlencode
from urllib.request import Request, urlopen

import pandas as pd

from collector.sources.base import Source, SourceError
from collector.sources.constants import (
    ANN_CNINFO_MAX_PAGES,
    ANN_EASTMONEY_MAX_PAGES,
    ANN_EASTMONEY_PAGE_SIZE,
    ANN_EXISTING_IDS_LIMIT,
    ANN_HTTP_TIMEOUT,
    ANN_LOOKBACK_DAYS,
    ANN_PAGE_INTERVAL,
    ANN_RETRY_ATTEMPTS,
    ANN_STOCKS_ENV,
    ANN_UA,
    ANNOUNCEMENT_MAJOR_COLUMNS,
    CNINFO_MARKETS,
    CNINFO_PAGE_SIZE,
    CNINFO_STATIC_URL,
    CNINFO_TOPSEARCH_URL,
    CNINFO_URL,
    EASTMONEY_ANN_PDF_URL,
    EASTMONEY_ANN_URL,
)

logger = logging.getLogger(__name__)

ANN_COLUMNS = [
    "source",
    "external_id",
    "stock_code",
    "stock_name",
    "title",
    "ann_type_source",
    "major",
    "published_at",
    "pdf_url",
]

# intelligence_announcement.source 列值（VARCHAR(16)，装不下 registry source_id）——探测报告 §3.3/§4.3
CNINFO_DB_SOURCE = "cninfo"
EASTMONEY_ANN_DB_SOURCE = "eastmoney_ann"

# 北京时间无夏令时，固定 UTC+8 与 Asia/Shanghai 等价（不引 zoneinfo，容器镜像无 tzdata 依赖）
_UTC8 = dt.timezone(dt.timedelta(hours=8))

# 全市场直判栏目并集（探测报告 §3.4 矩阵；报告建议文案「9 个」系计数误差，矩阵并集实为 10）
_CNINFO_MAJOR_CATEGORIES = sorted(set().union(*ANNOUNCEMENT_MAJOR_COLUMNS.values()))

# 东财备源 column_name → 6 类直判关键词（探测报告 §4 备源映射中与巨潮 6 类对齐的部分）。
# 增持/减持/回购/关联交易 4 类东财虽有专属栏目（增持/减持/回购/关联交易），但控制器裁定
# 不做全市场扫描——关键词表刻意不含，全市场条目据此过滤、关注集条目 major=false 由 LLM 判定。
_EASTMONEY_MAJOR_KEYWORDS: dict[str, tuple[str, ...]] = {
    "PERIODIC_REPORT": ("报告全文", "报告摘要"),
    "EARNINGS_FORECAST": ("业绩预告",),
    "EARNINGS_FLASH": ("业绩快报",),
    "PLACEMENT": ("增发", "配股"),
    "EQUITY_INCENTIVE": ("股权激励",),
    "DELISTING_RISK": ("退市", "风险警示"),
}


def _watchlist_stocks(params):
    """params['stocks']（list/逗号串）或 params['stocks_env']（env 变量名）→ 关注集代码列表。

    collector 不查业务表（控制器裁定）：关注集由任务 params / 环境变量注入；缺省回落
    ``ANN_STOCKS_ENV``（INTELLIGENCE_ANN_STOCKS，逗号分隔）。去重保序（重复代码会重复查询）。
    """
    raw = params.get("stocks")
    if raw is None:
        raw = os.environ.get(str(params.get("stocks_env") or ANN_STOCKS_ENV))
    if not raw:
        return []
    items = raw if isinstance(raw, (list, tuple)) else str(raw).split(",")
    codes: list[str] = []
    for item in items:
        code = str(item).strip()
        if code and code not in codes:
            codes.append(code)
    return codes


def _parse_em_time(raw):
    """`"2026-08-14 20:41:29:276"`（第三冒号后毫秒段，§4 P2 补测）→ UTC+8 aware datetime。

    毫秒段直接剥离（日粒度口径下亚秒精度无消费方，误当微秒反而失真）；不可解析返回 None。
    """
    s = str(raw or "").strip()
    if not s:
        return None
    if s.count(":") == 3:
        s = s.rsplit(":", 1)[0]
    try:
        return dt.datetime.strptime(s, "%Y-%m-%d %H:%M:%S").replace(tzinfo=_UTC8)
    except ValueError:
        return None


class _AnnouncementSourceBase(Source):
    """公告源共用骨架：输出列契约 / 窗口计算 / 已存集合查询 / 礼貌限速 / 单条丢弃告警。

    supports_range=False：公告任务为 MON-FRI 双跑增量语义，params 的 start/end 表达显式
    查询窗口（巨潮 seDate 真实生效、东财作翻页下界）而非 backfill 逐日分片意图。
    """

    supports_range = False
    db_source: str = ""
    default_page_size: int = CNINFO_PAGE_SIZE
    default_max_pages: int = ANN_CNINFO_MAX_PAGES

    def __init__(
        self,
        source_id,
        conn_factory=None,
        page_size=None,
        max_pages=None,
        interval=ANN_PAGE_INTERVAL,
        timeout=ANN_HTTP_TIMEOUT,
        attempts=ANN_RETRY_ATTEMPTS,
        sleep_fn=time.sleep,
        now_fn=None,
    ):
        self.source_id = source_id
        self.conn_factory = conn_factory  # 增量截断用：查本源已存 external_id 集合
        self.page_size = page_size if page_size is not None else self.default_page_size
        self.max_pages = max_pages if max_pages is not None else self.default_max_pages
        self.interval = interval
        self.timeout = timeout
        self.attempts = attempts
        self.sleep_fn = sleep_fn
        self.now_fn = now_fn or (lambda: dt.datetime.now(_UTC8))
        self._warnings: list[str] = []
        self._req_seq = 0

    # ---------------- 请求与限速

    def _polite(self):
        """相邻 HTTP 请求间礼貌间隔：公告主源每轮 30+ 条流，首个请求外统一限速（跨流/翻页/重试）。"""
        if self._req_seq > 0:
            self.sleep_fn(self.interval)
        self._req_seq += 1

    def _request_json(self, request):
        """单次 HTTP（GET/POST 由 Request 是否带 data 决定）→ JSON dict。

        请求级异常（网络/超时/非 200/JSON 解析）重试 attempts 次后抛 SourceError；
        结构漂移在 _items 校验，不进此路径。
        """
        last_error = None
        for _attempt in range(1, self.attempts + 1):
            self._polite()
            try:
                with urlopen(request, timeout=self.timeout) as resp:
                    status = resp.status
                    body = resp.read()
                if status != 200:
                    raise OSError(f"HTTP {status}")
                return json.loads(body.decode("utf-8"))
            except Exception as e:  # 请求级异常统一重试
                last_error = e
        raise SourceError(f"{self.source_id}: 连续 {self.attempts} 次请求/解析失败（{request.full_url}）：{last_error}")

    # ---------------- 增量与窗口

    def _existing_ids(self):
        """本源已存 external_id 集合（最近 ANN_EXISTING_IDS_LIMIT 条）。

        按本源 source 过滤：巨潮/东财 id 空间不相交，跨源混查会让备源首跑被主源存量
        「假截断」。集合覆盖不足只多翻页不丢数据（UPSERT 兜底）。
        """
        with self.conn_factory() as conn, conn.cursor() as cur:
            cur.execute(
                "SELECT external_id FROM intelligence_announcement WHERE source = %s ORDER BY id DESC LIMIT %s",
                (self.db_source, ANN_EXISTING_IDS_LIMIT),
            )
            return {row[0] for row in cur.fetchall()}

    def _parse_date(self, raw, label):
        """`"2026-09-29"` / `"20260929"`（backfill 紧凑式）→ date；不可解析抛 SourceError（配置错误须显性失败）。"""
        compact = str(raw).strip().replace("-", "")
        if len(compact) == 8 and compact.isdigit():
            try:
                return dt.date(int(compact[:4]), int(compact[4:6]), int(compact[6:8]))
            except ValueError:
                pass
        raise SourceError(f"{self.source_id}: params.{label} 不可解析为日期：{raw!r}")

    def _window(self, params):
        """params start/end → (start, end) 日期窗口；缺省 end=当日（北京时间）、start=end-回看天数。

        回看 ANN_LOOKBACK_DAYS 天覆盖周五晚→周一早的周末缺口（任务 MON-FRI 双跑，周六日
        披露由周一晨窗口回补）；重拉重叠由整页截断 + UPSERT 幂等兜住。
        """
        today = self.now_fn().date()
        end_date = self._parse_date(params["end"], "end") if params.get("end") else today
        if params.get("start"):
            start_date = self._parse_date(params["start"], "start")
        else:
            start_date = end_date - dt.timedelta(days=ANN_LOOKBACK_DAYS)
        return start_date, end_date

    def _flush_warnings(self):
        if self._warnings:
            sample = "；".join(self._warnings[:5])
            self.last_warnings = [f"单条解析告警 {len(self._warnings)} 条，样例：{sample}"]
            logger.warning("%s: %s", self.source_id, self.last_warnings[0])


class CninfoAnnouncementSource(_AnnouncementSourceBase):
    """巨潮公告主源（registry key `cninfo_ann` → DB source `cninfo`）。

    fetch 两阶段：①全市场 6 类直判栏目并集 × 三市场逐栏目扫描（条目 major=true）；
    ②关注集逐股无 category 全量（orgId 惰性解析缓存，条目 major=false，与①重复的
    announcementId 去重）。
    """

    db_source = CNINFO_DB_SOURCE

    def __init__(self, source_id, conn_factory=None, **kwargs):
        super().__init__(source_id, conn_factory=conn_factory, **kwargs)
        self._orgid_cache: dict[str, tuple[str, tuple[str, str]]] = {}  # code → (orgId, (column, plate))

    def _request_headers(self):
        # §3.1 实测请求形态：form-urlencoded + XHR 头（探测期 ~50 请求无频控封禁）
        return {
            "User-Agent": ANN_UA,
            "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
            "X-Requested-With": "XMLHttpRequest",
        }

    def _query_request(self, *, column, plate, category, stock, page_num, se_date):
        form = {
            "pageNum": page_num,
            "pageSize": self.page_size,
            "column": column,
            "tabName": "fulltext",
            "stock": stock,
            "searchkey": "",
            "secid": "",
            "plate": plate,
            "category": category,
            "trade": "",
            "seDate": se_date,
        }
        # safe 保留 , 与 ~ 字面（与实测 curl 形态一致；stock 值含逗号、seDate 含 ~）
        body = urlencode(form, safe=",:~").encode("utf-8")
        return Request(CNINFO_URL, data=body, headers=self._request_headers())

    def _items(self, payload):
        """结构校验 + 取条目列表。

        实测空结果形态是 announcements 键**缺失** + totalRecordNum=0（P2 补测）——键缺失
        且总数为零按空页处理；有总数却缺键 / 值非列表视为上游结构漂移，直接 SourceError。
        """
        if not isinstance(payload, dict):
            raise SourceError(f"{self.source_id}: 响应结构漂移（非 JSON 对象），上游可能已变更")
        anns = payload.get("announcements")
        if anns is None:
            total = payload.get("totalRecordNum")
            if total not in (None, 0):
                raise SourceError(f"{self.source_id}: 响应结构漂移（totalRecordNum={total} 却缺条目），上游可能已变更")
            return []
        if not isinstance(anns, list):
            raise SourceError(f"{self.source_id}: 响应结构漂移（announcements 非列表），上游可能已变更")
        return anns

    def _parse_item(self, item, external_id, major):
        title = str(item.get("announcementTitle") or "").strip()
        stock_code = str(item.get("secCode") or "").strip()
        if not title:  # title NOT NULL，缺失即丢弃
            self._warnings.append(f"{external_id} 缺 announcementTitle，已丢弃")
            return None
        if not stock_code:  # stock_code NOT NULL
            self._warnings.append(f"{external_id} 缺 secCode，已丢弃")
            return None
        try:
            published = dt.datetime.fromtimestamp(int(item.get("announcementTime")) / 1000, _UTC8)
        except (TypeError, ValueError, OSError, OverflowError):
            self._warnings.append(f"{external_id} announcementTime 不可解析：{item.get('announcementTime')!r}，已丢弃")
            return None
        adjunct = str(item.get("adjunctUrl") or "").strip()
        ann_type = str(item.get("announcementType") or "").strip()  # §3.3：分类码链原样入库
        return {
            "source": self.db_source,
            "external_id": external_id,
            "stock_code": stock_code,
            "stock_name": str(item.get("secName") or "").strip() or None,
            "title": title,
            "ann_type_source": ann_type[:64] or None,  # 列宽 64 防御截断
            "major": major,
            "published_at": published,
            "pdf_url": f"{CNINFO_STATIC_URL}{adjunct}" if adjunct else None,  # §3.3 实测拼接规则
        }

    def _scan_stream(self, rows, seen, existing, column, plate, *, category, stock, major, se_date):
        """单条查询流（一个 market×category 或一只关注股）分页拉取。

        停页条件：整页无新增（增量截断，见模块 docstring 的整页判定依据）/ hasMore 明确
        False / 空页 / max_pages 上限。已存（existing）与已输出（seen）条目跳过不输出。
        """
        for page_num in range(1, self.max_pages + 1):
            payload = self._request_json(
                self._query_request(
                    column=column, plate=plate, category=category, stock=stock, page_num=page_num, se_date=se_date
                )
            )
            anns = self._items(payload)
            if not anns:
                break
            fresh = 0
            for item in anns:
                external_id = str(item.get("announcementId") or "").strip() if isinstance(item, dict) else ""
                if not external_id:
                    self._warnings.append(f"条目缺 announcementId，已丢弃：{str(item)[:60]!r}")
                    continue
                if external_id in seen or (existing is not None and external_id in existing):
                    continue
                row = self._parse_item(item, external_id, major)
                if row is None:
                    continue
                rows.append(row)
                seen.add(external_id)
                fresh += 1
            if fresh == 0:
                break  # 整页无新增：其后的页严格更旧（日粒度倒序），截断
            if payload.get("hasMore") is False:
                break  # 服务端明确无更多页（实测单页结果 hasMore=False）

    def _resolve_orgid(self, code):
        """topSearch 解析 code → (orgId, (column, plate))，进程内缓存（orgId 长期稳定，§3.1）。

        解析失败（无命中 / plate 不明 / 接口异常）软降级：记 last_warnings 跳过该标的、
        不缓存失败（下次 fetch 重试）——其 6 类直判条目仍由全市场栏目扫描覆盖。
        """
        cached = self._orgid_cache.get(code)
        if cached is not None:
            return cached
        try:
            payload = self._request_json(
                Request(
                    CNINFO_TOPSEARCH_URL,
                    data=urlencode({"keyWord": code, "maxSecNum": 10, "maxListNum": 5}).encode("utf-8"),
                    headers=self._request_headers(),
                )
            )
        except SourceError as e:
            self._warnings.append(f"topSearch 解析 {code} 失败，该标的全量采集跳过：{e}")
            return None
        hit = None
        if isinstance(payload, dict):
            keyboard = payload.get("keyBoardList")
            for entry in keyboard if isinstance(keyboard, list) else []:
                if not isinstance(entry, dict) or str(entry.get("code") or "").strip() != code:
                    continue  # 联想结果混有他股/H债，须精确 code 匹配
                market = CNINFO_MARKETS.get(str(entry.get("plate") or "").strip())
                org_id = str(entry.get("orgId") or "").strip()
                if market and org_id:
                    hit = (org_id, market)
                    break
        if hit is None:
            self._warnings.append(f"topSearch 未解析到 {code} 的 orgId/市场，该标的全量采集跳过")
            return None
        self._orgid_cache[code] = hit
        return hit

    def fetch(self, params):
        self.last_warnings = None  # 单例源：观测状态每次 fetch 重置
        self._warnings = []
        self._req_seq = 0
        start_date, end_date = self._window(params)
        se_date = f"{start_date.isoformat()}~{end_date.isoformat()}"
        # 显式 start/end 给出完整窗口（含历史区间重拉），跳过已存截断
        incremental = not ("start" in params or "end" in params)
        existing = self._existing_ids() if incremental and self.conn_factory is not None else None
        rows: list[dict] = []
        seen: set[str] = set()
        # 阶段一（全市场 6 类直判）：直判栏目并集 × 三市场；增减持/回购/关联交易宽栏目不扫（控制器裁定）
        for column, plate in CNINFO_MARKETS.values():
            for category in _CNINFO_MAJOR_CATEGORIES:
                self._scan_stream(
                    rows, seen, existing, column, plate, category=category, stock="", major=True, se_date=se_date
                )
        # 阶段二（关注集全量）：逐股无 category（orgId 惰性解析缓存）；6 类直判条目已在阶段一
        # 以 major=true 采得，seen 去重；其余栏目条目 major=false 由 LLM 标签判定（P2 Task 5）
        for code in _watchlist_stocks(params):
            resolved = self._resolve_orgid(code)
            if resolved is None:
                continue
            org_id, (column, plate) = resolved
            self._scan_stream(
                rows, seen, existing, column, plate, category="", stock=f"{code},{org_id}", major=False, se_date=se_date
            )
        self._flush_warnings()
        return pd.DataFrame(rows, columns=ANN_COLUMNS)


class EastmoneyAnnouncementSource(_AnnouncementSourceBase):
    """东财公告流降级源（registry key `eastmoney_ann` → DB source `eastmoney_ann`）。

    selector 语义（D2）：巨潮不可达/结构漂移时才落到本源，主源可用时备源不采。
    fetch 两阶段：①全市场流（f_node=0）翻页 + column_name 关键词过滤 6 类直判条目
    （major=true）；②关注集 stock_list 多股一次流全量（major=false，与①重复的 art_code
    去重）。
    """

    db_source = EASTMONEY_ANN_DB_SOURCE
    default_page_size = ANN_EASTMONEY_PAGE_SIZE
    default_max_pages = ANN_EASTMONEY_MAX_PAGES

    def _page_request(self, page_index, stock_list):
        query = urlencode(
            {
                "sr": "-1",  # 新→旧（notice_date/sort_date 日粒度严格倒序，P2 补测）
                "page_size": self.page_size,
                "page_index": page_index,
                "ann_type": "A",  # 沪深北三市（920982 实测 1280 条）
                "client_source": "web",
                "stock_list": stock_list,  # 空 = 全市场流（实测空值等价缺省）
                "f_node": "0",  # 不做一级栏目预筛：column_name 关键词为权威判据（§4）
                "s_node": "0",
            }
        )
        return Request(f"{EASTMONEY_ANN_URL}?{query}", headers={"User-Agent": ANN_UA})

    def _items(self, payload):
        if not isinstance(payload, dict) or payload.get("success") != 1:
            raise SourceError(f"{self.source_id}: 东财公告流接口 success={payload.get('success')!r}，不可用")
        data = payload.get("data")
        items = data.get("list") if isinstance(data, dict) else None
        if not isinstance(items, list):
            raise SourceError(f"{self.source_id}: 响应结构漂移（data.list 缺失），上游可能已变更")
        return items

    @staticmethod
    def _notice_date(item):
        """notice_date（日粒度，晚间披露进位次日——与巨潮 announcementTime 同口径）→ date；缺失返回 None。"""
        parsed = _parse_em_time(item.get("notice_date"))
        return parsed.date() if parsed else None

    @staticmethod
    def _is_major_column(names):
        """column_name 命中 6 类直判关键词（§4 备源映射）→ major 预判。"""
        for name in names:
            for keywords in _EASTMONEY_MAJOR_KEYWORDS.values():
                if any(kw in name for kw in keywords):
                    return True
        return False

    def _parse_item(self, item, external_id):
        title = str(item.get("title") or "").strip()
        if not title:  # title NOT NULL，缺失即丢弃
            self._warnings.append(f"{external_id} 缺 title，已丢弃")
            return None
        codes = [c for c in (item.get("codes") or []) if isinstance(c, dict)]
        stock_code = str(codes[0].get("stock_code") or "").strip() if codes else ""
        if not stock_code:  # stock_code NOT NULL；多标的公告取 codes[0]（业务键单行约束）
            self._warnings.append(f"{external_id} 缺 codes[].stock_code，已丢弃")
            return None
        published = _parse_em_time(item.get("display_time")) or _parse_em_time(item.get("notice_date"))
        if published is None:
            raw_times = f"{item.get('display_time')!r}/{item.get('notice_date')!r}"
            self._warnings.append(f"{external_id} display_time/notice_date 均不可解析：{raw_times}，已丢弃")
            return None
        names = [str(c.get("column_name") or "").strip() for c in (item.get("columns") or []) if isinstance(c, dict)]
        names = [n for n in names if n]
        return {
            "source": self.db_source,
            "external_id": external_id,
            "stock_code": stock_code,
            "stock_name": str(codes[0].get("short_name") or "").strip() or None,
            "title": title,
            # §4：源站中文栏目，多栏目 || 连接（对齐巨潮码链风格），列宽 64 防御截断
            "ann_type_source": "||".join(names)[:64] or None,
            "major": self._is_major_column(names),
            "published_at": published,
            "pdf_url": EASTMONEY_ANN_PDF_URL.format(art_code=external_id),  # 首附件直链（P2 补测实测）
        }

    def _stream(self, rows, seen, existing, *, stock_list, major_only, floor):
        """单条流分页拉取（新→旧）至 窗口下界 / 整页无新增 / 空页 / max_pages。

        页间实测有重叠（P2 补测 4/50：活跃时段头部插入致窗口漂移）→ run 级 seen 去重；
        截断按整页判定（display_time 毫秒位秒内乱序，同巨潮依据）。fresh 计「未见过且未
        入库」的条目数（含被 major 过滤的），否则全市场流会在首个全非直判页误判截断。
        """
        for page_index in range(1, self.max_pages + 1):
            payload = self._request_json(self._page_request(page_index, stock_list))
            items = self._items(payload)
            if not items:
                break
            fresh = 0
            for item in items:
                external_id = str(item.get("art_code") or "").strip() if isinstance(item, dict) else ""
                if not external_id:
                    self._warnings.append(f"条目缺 art_code，已丢弃：{str(item)[:60]!r}")
                    continue
                if external_id in seen or (existing is not None and external_id in existing):
                    continue
                notice = self._notice_date(item)
                if notice is not None and notice < floor:
                    return  # notice_date 日粒度严格倒序：首条低于窗口下界即整流终止
                row = self._parse_item(item, external_id)
                if row is None:
                    continue
                fresh += 1
                if major_only and not row["major"]:
                    continue  # 全市场过滤：栏目不在 6 类直判映射 → 不输出（控制器裁定）
                rows.append(row)
                seen.add(external_id)
            if fresh == 0:
                break

    def fetch(self, params):
        self.last_warnings = None
        self._warnings = []
        self._req_seq = 0
        start_date, _end = self._window(params)  # 流式新→旧无 end 上界参数，start 作翻页下界
        incremental = not ("start" in params or "end" in params)
        existing = self._existing_ids() if incremental and self.conn_factory is not None else None
        rows: list[dict] = []
        seen: set[str] = set()
        # 阶段一（全市场）：公告流全量翻页 + 6 类直判关键词过滤
        self._stream(rows, seen, existing, stock_list="", major_only=True, floor=start_date)
        # 阶段二（关注集全量）：stock_list 多股一次流，不按栏目过滤
        stocks = _watchlist_stocks(params)
        if stocks:
            self._stream(rows, seen, existing, stock_list=",".join(stocks), major_only=False, floor=start_date)
        self._flush_warnings()
        return pd.DataFrame(rows, columns=ANN_COLUMNS)
