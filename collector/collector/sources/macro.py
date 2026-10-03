r"""MS-22 P3 Task 2 宏观指标参数化源：MacroPageSource ×6（cpi/ppi/pmi/lpr/socfin/m2）。

取数路径/解析坑全部钉在探测报告实测（09-调研报告/2026-10-02-MS22-宏观取数页适配探测.md）：

- ``stats_table``（CPI/PPI/PMI，§1）：统计局 zxfb 列表发现（标题关键词定位 + ``./YYYYMM/t….html``
  相对链接，同一条目在列表 HTML 出现 3 次按 href 去重；静态翻页 ``index_{N-1}.html``）→ 详情
  表格解析。CPI/PPI：**双副本表格取第一个**、headline 行（CPI=「居民消费价格」、PPI=「一、
  工业生产者出厂价格」去序号前缀）按**列序固定**取 环比|同比|累计（第三列表头文字随期别动态
  变化，勿按表头文字定位列）——列 3 同比 → value、列 2 环比 → source_note；PMI：**表 0 末行**
  （13 个月升序、最新月在末行）× 列头定位 PMI 列，表格漂移时正文
  ``制造业采购经理指数（PMI）为([\d.]+)%`` 二级路径兜底；期别一律从标题 ``(\d{4})年(\d{1,2})月``
  提取后补零。
- ``pboc_text``（LPR，§2）：3876551 列表 → 详情一句式纯文本正则 ``1年期LPR为([\d.]+)%``（5 年期
  入 source_note）。**期别从标题取**（DAY 型 ``…月…日…贷款市场报价利率``）——URL slug 为建页
  时间戳 ≠ 发布日（实测 4 月公告 slug=20260417…、标题 4 月 20 日），勿用 slug 推期别。
- ``pboc_xlsx``（socfin 主源→indicator=AFMI / m2 备源→indicator=M2，§3~§4）：两跳——列表页
  定位标题文本段（社融「社会融资规模增量统计表」注意与 Stock 表区分；M2「货币供应量」；标题在
  纯文本 div 非锚点，附件 htm/xlsx/pdf 为其后独立 ``<a>`` 且 xlsx 锚可见文本是「xls」，按 href
  后缀判）→ 其后首个 ``.xlsx`` 附件链接（文件名为上传时间戳无模板可推）→ openpyxl 解析
  （pandas.read_excel，openpyxl 已随 akshare 锁定在 requirements，无需新依赖）。社融 Flow 表
  **行方向**：月份列头行后 B 列（社会融资规模增量）非空的**最后一行**（全年 12 个月预置、
  未发布月 B 列为空是实测坑）；M2 **列方向**：月列头行（D..O）×「货币和准货币（M2）」行取
  **最后一个非空**列。M2 落库 indicator=M2 而非冒充 AFMI（D19：月度粒度每期仅一口径）。
  页面仅挂最新一期（历史在往年段数字节点页，v1 硬编码当年段 URL，跨年 backfill 属 URL 级
  扩展、探测报告 §3.3 备案）。

输出列 ``MACRO_COLUMNS``（目标表 ``intelligence_macro_series``，业务键 ``(indicator, period)``
UPSERT 幂等）；**yoy 恒 None**（裁定：CPI/PPI 同比即主值、PMI 水平指数无同比、Flow/M2 表无
同比列——探测报告 §1.2/§3 裁定不计算）；value 为 Decimal（整值去 ``.0`` 尾，NUMERIC(18,4) 落库）。

增量策略：conn_factory 查本指标已存 period 集合，列表（新→旧）**首见已存期别即停**（重复 cron
窗口多跑幂等）；显式 ``params['start']/['end']``（YYYY-MM 或 YYYY-MM-DD，字典序比较）为
backfill 意图——跳过截断翻满 max_pages 并按期别串序过滤窗口外条目。supports_range=True（月度
历史回补；LPR 日频同理）。

异常语义（沿 news/announcements 先例，fix round 1 细化）：请求级失败（网络/超时/非 404/410
状态/解码）重试 ``MACRO_RETRY_ATTEMPTS`` 次后抛 ``SourceError`` 走 selector 换源（**不自吞**）；
**单条**结构漂移（详情表格/正文/xlsx 解析空帧、详情或附件 404——``_DriftError``）按条丢弃记
``last_warnings`` 继续，**不毒化整轮**（如 LPR 同栏「带日期标题但正文无 LPR 句式」的公告滞留
列表只持续告警，不每月失败整任务）；仅当**整轮产出为空**（零关键词命中或命中条目全部解析
失败）且非「已存全命中」/「显式窗口过滤净空」时，收口聚合抛漂移 ``SourceError``（增量已存
全命中的空产出不算漂移）。翻页页码 >1 的 404/410 = 上游静态存档有界越界——**干净终止**
（已采行保留正常提交）；首页 404 仍按请求失败显性告警。
"""

import datetime as dt
import io
import logging
import re
import time
from decimal import Decimal, InvalidOperation
from html.parser import HTMLParser
from urllib.parse import urljoin
from urllib.request import Request, urlopen

import pandas as pd

from collector.sources.base import Source, SourceError
from collector.sources.constants import (
    MACRO_EXISTING_PERIODS_LIMIT,
    MACRO_HTTP_TIMEOUT,
    MACRO_LIST_URL_LPR,
    MACRO_LIST_URL_M2,
    MACRO_LIST_URL_SOCFIN,
    MACRO_LIST_URL_STATS,
    MACRO_PAGE_INTERVAL,
    MACRO_RETRY_ATTEMPTS,
    MACRO_STATS_MAX_PAGES,
    MACRO_UA,
)

logger = logging.getLogger(__name__)

MACRO_COLUMNS = ["indicator", "period", "period_type", "value", "yoy", "source_url", "source_note"]

# 北京时间无夏令时，固定 UTC+8 与 Asia/Shanghai 等价（不引 zoneinfo，容器镜像无 tzdata 依赖）
_UTC8 = dt.timezone(dt.timedelta(hours=8))

_PARSER_KINDS = ("stats_table", "pboc_text", "pboc_xlsx")

_TITLE_PERIOD_MONTH = re.compile(r"(\d{4})年(\d{1,2})月")
_TITLE_PERIOD_DAY = re.compile(r"(\d{4})年(\d{1,2})月(\d{1,2})日")
_ROW_PERIOD = re.compile(r"^(\d{4})年(\d{1,2})月")
_CELL_PERIOD = re.compile(r"^(\d{4})\.(\d{1,2})$")  # xlsx 月 cell：字符串或 pandas float 转串均可匹配
_SEQ_PREFIX = re.compile(r"^[一二三四五六七八九十]+、")
_PMI_BODY_RE = re.compile(r"制造业采购经理指数[（(]PMI[)）]为([\d.]+)%")
_LPR_1Y_RE = re.compile(r"1年期LPR为([\d.]+)%")
_LPR_5Y_RE = re.compile(r"5年期以上LPR为([\d.]+)%")
_STATS_DETAIL_HREF = re.compile(r"/sj/zxfb/\d{6}/t\d{8}_\d+\.s?html?$")
_LPR_DETAIL_HREF = re.compile(r"/zhengcehuobisi/125207/125213/125440/3876551/[^/]+/index\.html$")
_XLSX_HREF = re.compile(r"\.xlsx(?:$|\?)", re.IGNORECASE)  # 仅 .xlsx（.xls 为旧二进制日程附件，§3.4 不采）
_NUMERIC = re.compile(r"^-?\d+(?:\.\d+)?$")


# ---------------------------------------------------------------- HTML 解析（标准库 html.parser，无新依赖）


class _AnchorStreamParser(HTMLParser):
    """文档顺序收集 ``<a>``（href + title 属性 + 内文本）；三副本/附件链接由调用方按需去重定位。"""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.entries: list[tuple[str, str, str]] = []  # (href, title_attr, text)
        self._href: str | None = None
        self._title_attr = ""
        self._buf: list[str] = []

    def handle_starttag(self, tag, attrs):
        if tag == "a":
            attr = dict(attrs)
            self._href = attr.get("href") or ""
            self._title_attr = attr.get("title") or ""
            self._buf = []

    def handle_endtag(self, tag):
        if tag == "a" and self._href is not None:
            self.entries.append((self._href, self._title_attr, "".join(self._buf).strip()))
            self._href = None

    def handle_data(self, data):
        if self._href is not None:
            self._buf.append(data)


class _DocumentStreamParser(HTMLParser):
    """文档顺序事件流：锚点外连续文本段（合并 chunk）与 ``<a>``（href）交替。

    pboc_xlsx 两跳定位专用——调查统计司数据页条目标题在纯文本 div、附件为独立锚点
    （§3.1 实测：``<div class="titp20">社会融资规模增量统计表 …</div>`` 后随 htm/xlsx/pdf 三链）。
    """

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.events: list[tuple[str, str]] = []  # ("text", 段全文) / ("a", href)
        self._in_a = False
        self._run: list[str] = []

    def _flush_run(self):
        if self._run:
            self.events.append(("text", "".join(self._run)))
            self._run = []

    def handle_starttag(self, tag, attrs):
        if tag == "a":
            self._flush_run()
            self.events.append(("a", dict(attrs).get("href") or ""))
            self._in_a = True

    def handle_endtag(self, tag):
        if tag == "a":
            self._in_a = False

    def handle_data(self, data):
        if not self._in_a:
            self._run.append(data)

    def close(self):
        super().close()
        self._flush_run()


class _TablesParser(HTMLParser):
    """收集全部表格 ``table → row → cell 文本``（td/th 内嵌标签的文本并入所在 cell）。"""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.tables: list[list[list[str]]] = []
        self._depth = 0
        self._rows: list[list[str]] | None = None
        self._row: list[str] | None = None
        self._cell: list[str] | None = None

    def handle_starttag(self, tag, attrs):
        if tag == "table":
            self._depth += 1
            if self._depth == 1:
                self._rows = []
        elif self._depth and tag == "tr":
            self._row = []
        elif self._depth and tag in ("td", "th"):
            self._cell = []

    def handle_endtag(self, tag):
        if tag in ("td", "th") and self._cell is not None:
            if self._row is not None:
                self._row.append("".join(self._cell).strip())
            self._cell = None
        elif tag == "tr" and self._row is not None:
            if self._rows is not None:
                self._rows.append(self._row)
            self._row = None
        elif tag == "table" and self._depth:
            if self._depth == 1 and self._rows is not None:
                self.tables.append(self._rows)
                self._rows = None
            self._depth -= 1

    def handle_data(self, data):
        if self._cell is not None:
            self._cell.append(data)


class _VisibleTextParser(HTMLParser):
    """剥标签取可见文本（跳过 style/script），正文兜底正则用。"""

    _SKIP = {"style", "script"}

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self._chunks: list[str] = []
        self._skip = 0

    def handle_starttag(self, tag, attrs):
        if tag in self._SKIP:
            self._skip += 1

    def handle_endtag(self, tag):
        if tag in self._SKIP and self._skip:
            self._skip -= 1

    def handle_data(self, data):
        if not self._skip:
            self._chunks.append(data)

    def text(self):
        return "".join(self._chunks)


def _parse_tables(html):
    parser = _TablesParser()
    try:
        parser.feed(html)
        parser.close()
    except Exception as e:  # HTMLParser 极少抛（畸形标签容错强）；真异常按漂移处理
        raise _DriftError(f"详情页 HTML 解析失败：{e}") from e
    return parser.tables


def _visible_text(html):
    parser = _VisibleTextParser()
    try:
        parser.feed(html)
        parser.close()
    except Exception:
        return ""
    return parser.text()


def _to_decimal(raw):
    """cell/正则捕获 → Decimal；整值去 ``.0`` 尾（xlsx int/float 列两种 dtype 归一）；非数值返回 None。"""
    text = str(raw).strip()
    if not _NUMERIC.match(text):
        return None
    try:
        value = Decimal(text)
    except InvalidOperation:
        return None
    return value.quantize(Decimal(1)) if value == value.to_integral_value() else value


def _cells(row):
    """DataFrame 行 → strip 后的字符串 cell 列表（NaN/None → ""）。"""
    return ["" if c is None or (isinstance(c, float) and pd.isna(c)) else str(c).strip() for c in row]


class _DriftError(SourceError):
    """解析层结构漂移（表格/正文/xlsx 形态漂移、详情 404）：fetch 主循环按**单条**捕获丢弃
    （记 last_warnings 继续，不毒化整轮）；仅整轮空产出时收口聚合抛出。请求级失败（网络/
    非 404/410 状态）仍为基类 SourceError——直接穿透走 selector 换源，不进单条捕获。"""


# ---------------------------------------------------------------- 源实现


class MacroPageSource(Source):
    """宏观指标参数化页源：``MacroPageSource(source_id, indicator=…, period_type=…, parser_kind=…, url=…, …)``。

    三种 parser_kind 共用「列表页发现 → （必要时）详情解析」骨架，差异全部在钩子方法内；
    参数化实例规格见模块级 ``MACRO_SOURCE_SPECS``（jobs 注册时展开为构造 kwargs）。
    """

    supports_range = True

    def __init__(
        self,
        source_id,
        conn_factory=None,
        *,
        indicator,
        period_type,
        parser_kind,
        url,
        title_keyword,
        row_keyword=None,
        max_pages=1,
        interval=MACRO_PAGE_INTERVAL,
        timeout=MACRO_HTTP_TIMEOUT,
        attempts=MACRO_RETRY_ATTEMPTS,
        sleep_fn=time.sleep,
    ):
        if parser_kind not in _PARSER_KINDS:
            raise ValueError(f"parser_kind 须为 {_PARSER_KINDS} 之一，收到 {parser_kind!r}")
        self.source_id = source_id
        self.conn_factory = conn_factory  # 增量截断用：查本指标已存 period 集合
        self.indicator = indicator
        self.period_type = period_type
        self.parser_kind = parser_kind
        self.url = url
        self.title_keyword = title_keyword
        self.row_keyword = row_keyword  # stats_table 详情 headline 行指标名（CPI/PPI）
        self.max_pages = max_pages
        self.interval = interval
        self.timeout = timeout
        self.attempts = attempts
        self.sleep_fn = sleep_fn
        self._warnings: list[str] = []
        self._req_seq = 0

    # ---------------- 请求

    def _polite(self):
        """相邻 HTTP 请求间礼貌间隔（列表/详情/翻页/重试跨段统一限速）。"""
        if self._req_seq > 0:
            self.sleep_fn(self.interval)
        self._req_seq += 1

    def _get(self, url, as_bytes=False, allow_missing=False):
        """单次 GET → str（utf-8 优先 gbk 兜底）/原始 bytes（xlsx）/None。

        请求级异常重试 attempts 次后抛 SourceError（走 selector 换源）；``allow_missing``
        时 404/410 不重试、返回 None——供翻页页码 >1（上游静态存档有界，越界即干净终止，
        已采行保留）与详情/附件链接（条目级软 404）使用；**首页**不得开 allow_missing
        （源真不可达须显性失败）。
        """
        last_error = None
        for _attempt in range(1, self.attempts + 1):
            self._polite()
            try:
                with urlopen(Request(url, headers={"User-Agent": MACRO_UA}), timeout=self.timeout) as resp:
                    status = resp.status
                    body = resp.read()
                if status != 200:
                    if allow_missing and status in (404, 410):
                        return None
                    raise OSError(f"HTTP {status}")
                if as_bytes:
                    return body
                try:
                    return body.decode("utf-8")
                except UnicodeDecodeError:
                    return body.decode("gbk")
            except Exception as e:  # 请求级异常统一重试；结构漂移在解析层抛，不进此路径
                last_error = e
        raise SourceError(f"{self.source_id}: 连续 {self.attempts} 次请求/解析失败（{url}）：{last_error}")

    # ---------------- 增量与窗口

    def _existing_periods(self):
        """本指标已存 period 集合（幂等键 indicator+period；月频/日频互不干扰）。"""
        with self.conn_factory() as conn, conn.cursor() as cur:
            cur.execute(
                "SELECT period FROM intelligence_macro_series WHERE indicator = %s ORDER BY period DESC LIMIT %s",
                (self.indicator, MACRO_EXISTING_PERIODS_LIMIT),
            )
            return {row[0] for row in cur.fetchall()}

    def _in_range(self, params, period):
        """显式 start/end（YYYY-MM 或 YYYY-MM-DD）按字典序比较过滤期别；未给区间恒通过。"""
        after_start = "start" not in params or period >= str(params["start"])
        before_end = "end" not in params or period <= str(params["end"])
        return after_start and before_end

    def _page_url(self, page_no):
        # 仅 zxfb 列表有静态翻页（index_1.html…，§1.1）；pbc 两类列表页仅挂当期，不翻页
        if self.parser_kind == "stats_table" and page_no > 1:
            return urljoin(self.url, f"index_{page_no - 1}.html")
        return self.url

    # ---------------- 列表发现

    def _list_entries(self, html):
        """列表页 → [(条目标题, 取数 URL)]（文档序、URL 去重；stats=详情页 / pboc_xlsx=附件直链）。"""
        parser = _AnchorStreamParser()
        try:
            parser.feed(html)
            parser.close()
        except Exception:
            return []
        if self.parser_kind == "pboc_xlsx":
            return self._xlsx_entries(html)
        pattern = _STATS_DETAIL_HREF if self.parser_kind == "stats_table" else _LPR_DETAIL_HREF
        entries: list[tuple[str, str]] = []
        seen: set[str] = set()
        for href, title_attr, text in parser.entries:
            resolved = urljoin(self.url, href)
            if not pattern.search(resolved):
                continue
            if resolved in seen:
                continue  # 同一条目多终端副本（§1.1 实测 ×3）
            seen.add(resolved)
            entries.append((title_attr or text, resolved))
        return entries

    def _xlsx_entries(self, html):
        """两跳定位（§3.1 实测形态）：条目标题在**纯文本 div**（非锚点），htm/xlsx/pdf 为其后
        独立 ``<a>`` 附件锚点（xlsx 锚的可见文本是「xls」，须按 href 后缀判）——按文档顺序取
        关键词文本段之后的首个 ``.xlsx`` 链接（同块 Stock/他条目的附件不会插在中间）。"""
        parser = _DocumentStreamParser()
        try:
            parser.feed(html)
            parser.close()
        except Exception:
            return []
        events = parser.events
        for idx, (kind, payload) in enumerate(events):
            if kind != "text" or self.title_keyword not in payload:
                continue
            for later_kind, later_payload in events[idx:]:
                if later_kind == "a" and _XLSX_HREF.search(later_payload):
                    return [(payload.strip(), urljoin(self.url, later_payload))]
            return []  # 标题命中但其后无 .xlsx 附件：收口按零命中漂移告警
        return []

    def _period_from_title(self, title):
        """标题 → 期别（月 ``2026-09`` / 日 ``2026-09-20``）；不可解析返回 None（调用方记告警）。"""
        if self.period_type == "DAY":
            m = _TITLE_PERIOD_DAY.search(title)
            return f"{m.group(1)}-{int(m.group(2)):02d}-{int(m.group(3)):02d}" if m else None
        m = _TITLE_PERIOD_MONTH.search(title)
        return f"{m.group(1)}-{int(m.group(2)):02d}" if m else None

    # ---------------- 详情解析（三形态）

    def _parse_detail(self, url, title, period_hint):
        if self.parser_kind == "stats_table":
            return self._parse_stats_detail(url, title, period_hint)
        if self.parser_kind == "pboc_text":
            return self._parse_lpr_detail(url, period_hint)
        return self._parse_xlsx_detail(url)

    def _parse_stats_detail(self, url, title, period_hint):
        html = self._get(url, allow_missing=True)  # 详情条目级 404/410 → 单条软丢弃
        if html is None:
            raise _DriftError(f"{self.source_id}: 详情页 404/410（条目可能已删除）：{url}")
        if self.indicator == "PMI":
            return self._parse_pmi(html, title, url, period_hint)
        return self._parse_cpi_ppi(html, title, url, period_hint)

    def _parse_cpi_ppi(self, html, title, url, period_hint):
        tables = _parse_tables(html)
        if not tables:
            raise _DriftError(f"{self.source_id}: 详情页无表格（结构漂移），上游可能已变更：{url}")
        for row in tables[0]:  # 双副本内容相同，取第一个表（§1.2）
            cells = [c.strip() for c in row]
            if len(cells) < 3:
                continue
            name = _SEQ_PREFIX.sub("", cells[0])
            value = _to_decimal(cells[2])  # 列序固定：环比|同比|累计——第三列表头文字随期别变，勿按表头定位
            if name == self.row_keyword and value is not None:
                return self._row(period_hint, value, url, f"环比涨跌幅（%）{cells[1].strip()}")
        raise _DriftError(f"{self.source_id}: 详情表格未找到「{self.row_keyword}」headline 行（结构漂移）：{url}")

    def _parse_pmi(self, html, title, url, period_hint):
        tables = _parse_tables(html)
        for table in tables:  # 表 0 = 制造业 PMI 及构成（§1.4）
            pmi_col = None
            for header in table:
                cells = [c.strip() for c in header]
                if "PMI" in cells:
                    pmi_col = cells.index("PMI")
                    break
            if pmi_col is None:
                continue
            best = None
            for row in table:
                cells = [c.strip() for c in row]
                m = _ROW_PERIOD.match(cells[0]) if cells else None
                value = _to_decimal(cells[pmi_col]) if pmi_col < len(cells) else None
                if m and value is not None:
                    best = (f"{m.group(1)}-{int(m.group(2)):02d}", value)  # 末行 = 最新月（13 个月升序）
            if best is not None:
                period, value = best
                return self._row(period, value, url, "制造业采购经理指数（PMI，%）")
        # 二级路径：正文兜底（表格漂移时，§1.4 实测句式）
        m = _PMI_BODY_RE.search(_visible_text(html))
        if m:
            return self._row(period_hint, _to_decimal(m.group(1)), url, "制造业采购经理指数（PMI，%）——正文兜底")
        raise _DriftError(f"{self.source_id}: PMI 表格与正文双路径均解析失败（结构漂移）：{url}")

    def _parse_lpr_detail(self, url, period_hint):
        html = self._get(url, allow_missing=True)
        if html is None:
            raise _DriftError(f"{self.source_id}: 详情页 404/410（条目可能已删除）：{url}")
        text = _visible_text(html)
        m = _LPR_1Y_RE.search(text)
        if not m:
            raise _DriftError(f"{self.source_id}: 详情正文未匹配「1年期LPR为…%」句式（结构漂移）：{url}")
        note = ""
        m5 = _LPR_5Y_RE.search(text)
        if m5:
            note = f"5年期以上LPR为{m5.group(1)}%"
        return self._row(period_hint, _to_decimal(m.group(1)), url, note)

    def _parse_xlsx_detail(self, url):
        body = self._get(url, as_bytes=True, allow_missing=True)
        if body is None:
            raise _DriftError(f"{self.source_id}: xlsx 附件 404/410（可能已被替换）：{url}")
        try:
            frame = pd.read_excel(io.BytesIO(body), engine="openpyxl", header=None)
        except Exception as e:
            raise _DriftError(f"{self.source_id}: xlsx 解析失败（{url}）：{e}") from e
        grid = [_cells(row) for row in frame.values.tolist()]
        if self.indicator == "AFMI":
            return self._parse_flow_grid(grid, url)
        return self._parse_money_grid(grid, url)

    def _parse_flow_grid(self, grid, url):
        """社融 Flow 表（行方向）：月份列头行后，「社会融资规模增量」列非空的最后一行。"""
        header_i = value_col = None
        for i, cells in enumerate(grid):
            if cells and cells[0] == "月份":
                header_i = i
                for j, cell in enumerate(cells):
                    if "社会融资规模增量" in cell:
                        value_col = j
                        break
                break
        if header_i is None or value_col is None:
            raise _DriftError(f"{self.source_id}: Flow xlsx 未找到「月份/社会融资规模增量」列头（结构漂移）：{url}")
        best = None
        for cells in grid[header_i + 1 :]:
            m = _CELL_PERIOD.match(cells[0]) if cells else None
            value = _to_decimal(cells[value_col]) if value_col < len(cells) else None
            if m and value is not None:  # 未来月预置行 B 列为空（None→""），不会覆盖 best（§3.1 坑）
                best = (f"{m.group(1)}-{int(m.group(2)):02d}", value)
        if best is None:
            raise _DriftError(f"{self.source_id}: Flow xlsx 无非空数据行（结构漂移）：{url}")
        period, value = best
        return self._row(period, value, url, "社会融资规模增量, 亿元人民币")

    def _parse_money_grid(self, grid, url):
        """货币供应量表（列方向）：月列头行 ×「货币和准货币（M2）」行，末个非空月列。"""
        month_cols: list[tuple[int, re.Match]] = []
        for cells in grid:
            cols = [(j, m) for j, cell in enumerate(cells) if (m := _CELL_PERIOD.match(cell))]
            if len(cols) >= 3:  # 连续月份列头（D..O 固定 12 列，§3.2）
                month_cols = cols
                break
        if not month_cols:
            raise _DriftError(f"{self.source_id}: Money Supply xlsx 未找到月份列头行（结构漂移）：{url}")
        for cells in grid:
            if not cells or "货币和准货币" not in cells[0] or "M2" not in cells[0]:
                continue
            for j, m in reversed(month_cols):  # 右起首个非空 = 最新已发布月
                value = _to_decimal(cells[j]) if j < len(cells) else None
                if value is not None:
                    period = f"{m.group(1)}-{int(m.group(2)):02d}"
                    return self._row(period, value, url, "货币和准货币（M2）期末余额, 亿元人民币")
        raise _DriftError(f"{self.source_id}: Money Supply xlsx 未找到「货币和准货币（M2）」数据行（结构漂移）：{url}")

    def _row(self, period, value, url, note):
        if period is None or value is None:
            raise _DriftError(f"{self.source_id}: 期别或数值解析为空（结构漂移）：{url}")
        return {
            "indicator": self.indicator,
            "period": period,
            "period_type": self.period_type,
            "value": value,
            "yoy": None,  # 裁定恒 null：详见模块 docstring
            "source_url": url,
            "source_note": (note or "").strip()[:255] or None,  # 列宽 255 防御截断
        }

    # ---------------- 主流程

    def fetch(self, params):
        self.last_warnings = None  # 单例源：观测状态每次 fetch 重置
        self._warnings = []
        self._req_seq = 0
        incremental = not ("start" in params or "end" in params)
        existing = self._existing_periods() if incremental and self.conn_factory is not None else None
        rows: list[dict] = []
        seen_periods: set[str] = set()
        truncated = False
        matched_any = False
        range_skipped = 0  # 命中关键词但被显式 start/end 窗口过滤的条目数（空产出合法的兜底判据）
        for page_no in range(1, self.max_pages + 1):
            # 页码 >1 的 404/410 = 静态存档有界越界：干净终止（已采行保留）；首页 404 仍按请求失败
            html = self._get(self._page_url(page_no), allow_missing=(page_no > 1))
            if html is None:
                break
            entries = self._list_entries(html)
            if not entries and page_no > 1:
                break  # 翻页空页；首页零条目交由收口判定（零关键词命中 → 漂移告警）
            for title, url in entries:
                if self.title_keyword not in title:
                    continue
                matched_any = True
                period = self._period_from_title(title)  # pboc_xlsx 无标题期别（None），期别在附件内
                if period is None and self.parser_kind != "pboc_xlsx":
                    self._warnings.append(f"标题期别不可解析，条目跳过：{title[:60]!r}")
                    continue
                if period is not None:
                    if period in seen_periods:
                        continue  # 同期次多篇文章（如 CPI 释放+图表稿），首篇已采
                    if existing is not None and period in existing:
                        truncated = True  # 首见已存期别：其后更旧条目均已入库
                        break
                    if not self._in_range(params, period):
                        range_skipped += 1
                        continue
                try:
                    row = self._parse_detail(url, title, period)
                except _DriftError as e:
                    # 单条结构漂移（毒条）：丢弃该条继续，不毒化整轮（如 LPR 同栏带日期标题但
                    # 正文无 LPR 句式的公告——滞留列表也只持续告警，不每月失败整任务）
                    self._warnings.append(f"条目解析失败已跳过（{title[:40]!r}）：{e}")
                    continue
                if row["period"] in seen_periods or (existing is not None and row["period"] in existing):
                    truncated = True  # xlsx 期别下载后方知：已存即本轮无新增
                    break
                if not self._in_range(params, row["period"]):
                    range_skipped += 1
                    continue
                rows.append(row)
                seen_periods.add(row["period"])
            if truncated:
                break
        if not rows and not truncated and not range_skipped:
            # 漂移即告警（收口聚合）：零关键词命中、或命中条目全部解析失败/无期别——宁可告警
            # 不可静默。「已存全命中」（truncated）与「显式窗口过滤净空」（range_skipped）不算。
            if matched_any:
                message = f"{self.source_id}: 命中条目全部解析失败（结构漂移）"
            else:
                message = f"{self.source_id}: 列表页未命中标题关键词「{self.title_keyword}」或其附件"
            raise _DriftError(f"{message}（结构漂移或未发布）：{self.url}")
        self._flush_warnings()
        return pd.DataFrame(rows, columns=MACRO_COLUMNS)

    def _flush_warnings(self):
        if self._warnings:
            sample = "；".join(self._warnings[:5])
            self.last_warnings = [f"单条解析告警 {len(self._warnings)} 条，样例：{sample}"]
            logger.warning("%s: %s", self.source_id, self.last_warnings[0])


# ---------------------------------------------------------------- 装配规格（jobs.py 注册时展开为构造 kwargs）


MACRO_SOURCE_SPECS: dict[str, dict] = {
    "cpi": {
        "indicator": "CPI",
        "period_type": "MONTH",
        "parser_kind": "stats_table",
        "url": MACRO_LIST_URL_STATS,
        "title_keyword": "居民消费价格",
        "row_keyword": "居民消费价格",
        "max_pages": MACRO_STATS_MAX_PAGES,
    },
    "ppi": {
        "indicator": "PPI",
        "period_type": "MONTH",
        "parser_kind": "stats_table",
        "url": MACRO_LIST_URL_STATS,
        "title_keyword": "工业生产者出厂价格",
        "row_keyword": "工业生产者出厂价格",
        "max_pages": MACRO_STATS_MAX_PAGES,
    },
    "pmi": {
        "indicator": "PMI",
        "period_type": "MONTH",
        "parser_kind": "stats_table",
        "url": MACRO_LIST_URL_STATS,
        "title_keyword": "采购经理指数",
        "max_pages": MACRO_STATS_MAX_PAGES,
    },
    "lpr": {
        "indicator": "LPR",
        "period_type": "DAY",
        "parser_kind": "pboc_text",
        "url": MACRO_LIST_URL_LPR,
        "title_keyword": "贷款市场报价利率",
        "max_pages": 1,
    },
    # afmi 双源（macro_afmi 任务 source_ids=[socfin, m2]）：主源 socfin → indicator=AFMI；
    # 备源 m2 → indicator=M2（不冒充 AFMI，降级留痕语义见设计 D19 / 探测报告 §4）
    "socfin": {
        "indicator": "AFMI",
        "period_type": "MONTH",
        "parser_kind": "pboc_xlsx",
        "url": MACRO_LIST_URL_SOCFIN,
        "title_keyword": "社会融资规模增量统计表",
        "max_pages": 1,
    },
    "m2": {
        "indicator": "M2",
        "period_type": "MONTH",
        "parser_kind": "pboc_xlsx",
        "url": MACRO_LIST_URL_M2,
        "title_keyword": "货币供应量",
        "max_pages": 1,
    },
}
