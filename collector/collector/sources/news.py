"""MS-20 P1 Task 6 新闻两源：东财 7×24 快讯主源 + 新浪 zhibo 降级源。

URL/字段/游标语义全部钉在探测报告实测（09-调研报告/2026-09-29-MS20-数据源探测报告.md §1/§2）：

- 东财 ``EastmoneyFastNewsSource``（DB source 列值 ``eastmoney_724``）：
  ``data.fastNewsList[]``，条目级 ``code`` 是唯一 id（与顶层接口状态码 ``code="1"`` 重名勿混淆）；
  ``sortEnd`` 游标翻页（首页传空、其后传上页 ``data.sortEnd``，恒等于本页末条 ``realSort``）；
  ``showTime`` 北京时间无时区后缀，按 UTC+8 解析；无独立 URL 字段，按 §1.3 模板拼接详情页。
  ``stockList`` 为前缀字符串数组（``1.``沪 / ``0.``深北 / ``90.BK``板块 / ``150.``基金），
  **原样入库**（报告 §1.3/§1.5、V3 迁移注释「源站标的标签原样」）——注意 ``0.`` 前缀段混有
  深市基金代码（实测 0.161032 为 LOF），A 股/板块的判定规则（6 位代码 60/68/00/30/92 开头
  + ``90.BK`` 行业码、``150.`` 与基金码忽略）属**抽取侧解析职责**，采集侧不做过滤、无信息损失。
- 新浪 ``SinaZhiboNewsSource``（DB source 列值 ``sina_zhibo``）：``result.data.feed.list[]``，
  ``page`` 页码翻页；``rich_text`` 无独立 title，剥前导【】段（无则退化全文）；
  ``ext`` 是 JSON 字符串，其中 ``stocks[]`` 仅取 ``market=cn``（fund/us/hk 等不采），
  ``symbol`` 去市场前缀得 6 位代码、``key`` 为中文名；解析失败置空数组不阻塞（§2.5）。

两源输出列固定 ``NEWS_COLUMNS``（``stock_tags`` 为 JSON 字符串，Task 7 writer 写 JSONB），
目标表 ``intelligence_news_raw``（业务键 ``(source, external_id)``，UPSERT 幂等）。

增量策略（设计 D1）：conn_factory 查最近 200 条已存 external_id 做集合，分页拉取至
**首个已存 id 截断**；显式 ``params['start']/['end']``（backfill 意图）优先——跳过截断翻满
``max_pages``。已存集合是全源混合的（不按 source 过滤），双源由 selector 互斥执行、
external_id 空间互不相交，最坏多翻至 ``max_pages`` 兜底，UPSERT 天然去重。

异常语义：请求级失败（网络/超时/非 200/JSON 解析）重试 ``NEWS_RETRY_ATTEMPTS`` 次后抛
``SourceError`` 让 selector 换源（**不自吞**）；响应 200 但预期结构缺失（上游漂移）直接
``SourceError``；单条解析失败丢弃并记 ``last_warnings``（不改终态）。
"""

import datetime as dt
import json
import logging
import re
import time
from urllib.request import Request, urlopen

import pandas as pd

from collector.sources.base import Source, SourceError
from collector.sources.constants import (
    NEWS_EASTMONEY_DETAIL_URL,
    NEWS_EASTMONEY_QUERY,
    NEWS_EASTMONEY_URL,
    NEWS_HTTP_TIMEOUT,
    NEWS_MAX_PAGES,
    NEWS_PAGE_INTERVAL,
    NEWS_PAGE_SIZE,
    NEWS_RETRY_ATTEMPTS,
    NEWS_SINA_QUERY,
    NEWS_SINA_REFERER,
    NEWS_SINA_URL,
    NEWS_UA,
)

logger = logging.getLogger(__name__)

NEWS_COLUMNS = ["source", "external_id", "title", "summary", "published_at", "url", "stock_tags"]

# intelligence_news_raw.source 列值（VARCHAR(16)，装不下 registry source_id）——探测报告 §1.3/§2.3
EASTMONEY_DB_SOURCE = "eastmoney_724"
SINA_DB_SOURCE = "sina_zhibo"

# 北京时间无夏令时，固定 UTC+8 与 Asia/Shanghai 等价（不引 zoneinfo，容器镜像无 tzdata 依赖）
_UTC8 = dt.timezone(dt.timedelta(hours=8))

_SINA_TITLE = re.compile(r"^【(?P<title>.+?)】")
_SINA_MARKET_PREFIX = re.compile(r"^[A-Za-z]+")


def _parse_beijing(value):
    """`"2026-09-29 08:53:09"` → UTC+8 aware datetime；不可解析抛 ValueError。"""
    return dt.datetime.strptime(str(value).strip(), "%Y-%m-%d %H:%M:%S").replace(tzinfo=_UTC8)


class _PagedNewsSource(Source):
    """新闻分页源骨架：增量截断 / 请求重试 / 结构漂移告警 / 单条丢弃语义两源共用。

    supports_range=False：上游无日期过滤参数，params 的 start/end 仅表达 backfill 意图
    （跳过已存截断翻满 max_pages），不是区间回填语义。
    """

    supports_range = False

    db_source: str = ""

    def __init__(
        self,
        source_id,
        conn_factory=None,
        page_size=NEWS_PAGE_SIZE,
        max_pages=NEWS_MAX_PAGES,
        interval=NEWS_PAGE_INTERVAL,
        timeout=NEWS_HTTP_TIMEOUT,
        attempts=NEWS_RETRY_ATTEMPTS,
        sleep_fn=time.sleep,
    ):
        self.source_id = source_id
        self.conn_factory = conn_factory  # 增量截断用：查已存 external_id 集合
        self.page_size = page_size
        self.max_pages = max_pages
        self.interval = interval
        self.timeout = timeout
        self.attempts = attempts
        self.sleep_fn = sleep_fn
        self._warnings: list[str] = []

    # ---------------- 子类钩子

    def _request_headers(self):
        return {"User-Agent": NEWS_UA}

    def _page_url(self, token):
        raise NotImplementedError

    def _items(self, payload):
        """结构校验 + 取条目列表；结构漂移抛 SourceError。"""
        raise NotImplementedError

    def _next_token(self, payload, token):
        """下一页游标；返回 None 表示不再翻页。"""
        raise NotImplementedError

    def _external_id(self, item):
        raise NotImplementedError

    def _parse_item(self, item, external_id):
        """单条 → 输出行 dict；解析失败返回 None（子类自行记 _warnings）。"""
        raise NotImplementedError

    # ---------------- 公共骨架

    def _existing_ids(self):
        with self.conn_factory() as conn, conn.cursor() as cur:
            cur.execute("SELECT external_id FROM intelligence_news_raw ORDER BY id DESC LIMIT 200")
            return {row[0] for row in cur.fetchall()}

    def _request_json(self, url):
        """单页 GET → JSON dict。请求级异常（网络/非 200/JSON 解析）重试 attempts 次后抛 SourceError。"""
        req = Request(url, headers=self._request_headers())
        last_error = None
        for attempt in range(1, self.attempts + 1):
            if attempt > 1:
                self.sleep_fn(self.interval)
            try:
                with urlopen(req, timeout=self.timeout) as resp:
                    status = resp.status
                    body = resp.read()
                if status != 200:
                    raise OSError(f"HTTP {status}")
                try:
                    return json.loads(body.decode("utf-8"))
                except UnicodeDecodeError:
                    return json.loads(body.decode("gbk"))  # 新浪系接口偶用 gbk
            except Exception as e:  # 请求级异常统一重试；结构漂移在 _items 校验、不进此路径
                last_error = e
        raise SourceError(f"{self.source_id}: 连续 {self.attempts} 次请求/解析失败（{url}）：{last_error}")

    def fetch(self, params):
        self.last_warnings = None  # 单例源：观测状态每次 fetch 重置
        self._warnings = []
        # 显式 start/end（backfill 意图）优先于已存集合截断（D1）
        incremental = not ("start" in params or "end" in params)
        existing = self._existing_ids() if incremental and self.conn_factory is not None else None
        rows = []
        seen: set[str] = set()
        token = None
        for page_no in range(1, self.max_pages + 1):
            if page_no > 1:
                self.sleep_fn(self.interval)
            payload = self._request_json(self._page_url(token))
            items = self._items(payload)
            if not items:
                break
            truncated = False
            for item in items:
                external_id = self._external_id(item)
                if not external_id:
                    self._warnings.append(f"条目缺唯一 id，已丢弃：{str(item)[:60]!r}")
                    continue
                if external_id in seen:  # 页间重叠防御（实测零重叠，重复行交给 UPSERT 前拦截）
                    continue
                if existing is not None and external_id in existing:
                    truncated = True  # 首个已存 id 截断：其后的更旧条目均已入库
                    break
                row = self._parse_item(item, external_id)
                if row is None:
                    continue
                rows.append(row)
                seen.add(external_id)
            if truncated:
                break
            token = self._next_token(payload, token)
            if token is None:
                break
        self._flush_warnings()
        return pd.DataFrame(rows, columns=NEWS_COLUMNS)

    def _flush_warnings(self):
        if self._warnings:
            sample = "；".join(self._warnings[:5])
            self.last_warnings = [f"单条解析告警 {len(self._warnings)} 条，样例：{sample}"]
            logger.warning("%s: %s", self.source_id, self.last_warnings[0])


class EastmoneyFastNewsSource(_PagedNewsSource):
    """东财 7×24 快讯主源（registry key `eastmoney_fast_news` → DB source `eastmoney_724`）。"""

    db_source = EASTMONEY_DB_SOURCE

    def _page_url(self, token):
        query = NEWS_EASTMONEY_QUERY.format(cursor=token or "", page_size=self.page_size)
        return f"{NEWS_EASTMONEY_URL}?{query}"

    def _items(self, payload):
        data = payload.get("data") if isinstance(payload, dict) else None
        items = data.get("fastNewsList") if isinstance(data, dict) else None
        if not isinstance(items, list):
            raise SourceError(f"{self.source_id}: 响应结构漂移（data.fastNewsList 缺失），上游可能已变更")
        return items

    def _next_token(self, payload, token):
        nxt = str((payload.get("data") or {}).get("sortEnd") or "").strip()
        return nxt if nxt and nxt != token else None  # 游标缺失/不动 → 终止（防死循环）

    def _external_id(self, item):
        # 条目级 code 是唯一 id；顶层 code="1" 是接口状态码，重名勿混淆（§1.2）
        return str(item.get("code") or "").strip() if isinstance(item, dict) else ""

    def _parse_item(self, item, external_id):
        title = str(item.get("title") or "").strip()
        if not title:  # title NOT NULL，缺失即丢弃（快讯实测恒有 title）
            self._warnings.append(f"{external_id} 缺 title，已丢弃")
            return None
        try:
            published = _parse_beijing(item.get("showTime"))
        except ValueError:
            self._warnings.append(f"{external_id} showTime 不可解析：{item.get('showTime')!r}，已丢弃")
            return None
        return {
            "source": self.db_source,
            "external_id": external_id,
            "title": title,
            "summary": str(item.get("summary") or "").strip() or None,
            "published_at": published,
            "url": NEWS_EASTMONEY_DETAIL_URL.format(code=external_id),
            # stockList 原样（含 150. 基金 / 0. 前缀深市基金）：过滤规则属抽取侧，见模块 docstring
            "stock_tags": json.dumps(item.get("stockList") or [], ensure_ascii=False),
        }


class SinaZhiboNewsSource(_PagedNewsSource):
    """新浪 zhibo 财经直播降级源（registry key `sina_zhibo_news` → DB source `sina_zhibo`）。"""

    db_source = SINA_DB_SOURCE

    def _request_headers(self):
        return {"User-Agent": NEWS_UA, "Referer": NEWS_SINA_REFERER}

    def _page_url(self, token):
        query = NEWS_SINA_QUERY.format(page=token or 1, page_size=self.page_size)
        return f"{NEWS_SINA_URL}?{query}"

    def _items(self, payload):
        result = payload.get("result") if isinstance(payload, dict) else None
        status = result.get("status") if isinstance(result, dict) else None
        code = status.get("code") if isinstance(status, dict) else None
        if code != 0:
            raise SourceError(f"{self.source_id}: 新浪接口 status.code={code!r}，不可用")
        data = result.get("data") if isinstance(result, dict) else None
        feed = data.get("feed") if isinstance(data, dict) else None
        items = feed.get("list") if isinstance(feed, dict) else None
        if not isinstance(items, list):
            raise SourceError(f"{self.source_id}: 响应结构漂移（result.data.feed.list 缺失），上游可能已变更")
        return items

    def _next_token(self, payload, token):
        return (token or 1) + 1  # 页码递增，上限由 fetch 的 max_pages 控制

    def _external_id(self, item):
        return str(item.get("id") or "").strip() if isinstance(item, dict) else ""

    def _parse_item(self, item, external_id):
        rich = str(item.get("rich_text") or "").strip()
        if not rich:
            self._warnings.append(f"{external_id} rich_text 为空，已丢弃")
            return None
        try:
            published = _parse_beijing(item.get("create_time"))
        except ValueError:
            self._warnings.append(f"{external_id} create_time 不可解析：{item.get('create_time')!r}，已丢弃")
            return None
        matched = _SINA_TITLE.match(rich)
        ext_url, tags = self._ext_stocks(item.get("ext"))
        return {
            "source": self.db_source,
            "external_id": external_id,
            "title": matched.group("title") if matched else rich,  # 【】内段；无括号退化全文
            "summary": rich,  # 全文含【】前缀，与东财 summary 口径一致
            "published_at": published,
            "url": str(item.get("docurl") or "").strip() or ext_url or None,
            "stock_tags": json.dumps(tags, ensure_ascii=False),
        }

    def _ext_stocks(self, ext_raw):
        """ext(JSON 字符串) → (www 版 docurl, market=cn 标的 [{code,name}])。

        ext 解析失败 → 标的置空不阻塞（§2.5 实测建议）；仅取 market=cn，symbol 去市场前缀
        （sz002369 → 002369）、key 为中文名；fund/us/hk/foreign 等不采（§2.3）。
        """
        if not ext_raw:
            return None, []
        try:
            ext = json.loads(ext_raw)
        except (TypeError, ValueError):
            self._warnings.append(f"ext 解析失败，标的标签置空：{str(ext_raw)[:40]!r}")
            return None, []
        stocks = ext.get("stocks") if isinstance(ext, dict) else None
        tags = []
        for stock in stocks if isinstance(stocks, list) else []:
            if not isinstance(stock, dict) or stock.get("market") != "cn":
                continue
            code = _SINA_MARKET_PREFIX.sub("", str(stock.get("symbol") or "").strip())
            if not code:
                continue
            tags.append({"code": code, "name": str(stock.get("key") or "").strip()})
        docurl = str(ext.get("docurl") or "").strip() if isinstance(ext, dict) else ""
        return (docurl or None), tags
