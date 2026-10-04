r"""MS-22 P3 Task 2 四部委政策源：PolicySiteSource ×4（pboc/csrc/mof/stats）。

取数路径/解析坑全部钉在探测报告实测（09-调研报告/2026-10-02-MS22-宏观取数页适配探测.md §5）：

- ``trs_list``（pboc/mof/stats，三站 TRS 同构两跳）：服务端渲染列表 → 详情正文容器。
  - pboc：条目 ``/goutongjiaoliu/113456/113469/{slug}/index.html``（slug 前 14 位=发布时间戳，
    external_id 直取 slug）+ 兄弟 ``<span>`` 日期；翻页 ``11040-{N}.html``（N 从 2 起）；详情
    ``#zoom`` 容器 + ``meta createDate`` 精确发布时刻（胜列表日 00:00）。
  - mof：条目为司局子域**绝对链接**（http 协议原样入库，跨子域跳转正常）；翻页
    ``index_{N-1}.htm``；正文 ``TRS_Editor`` 容器（内嵌 CSS 由 style 跳除）。
  - stats：tzgg 栏目（控制器裁定，§5.4）与 zxfb 同款 ``./{YYYYMM}/t{date}_{id}.html``；翻页
    ``index_{N-1}.html``；正文 ``.txt-content`` 容器——**开头内联 ``<style>`` 块须剔除**（实测坑）。
  - external_id：pboc=slug、mof/stats=``t{date}_{id}`` 数字段；标题取 ``<a title>`` 属性优先。
- ``csrc_json``（csrc，四部委中最优）：``/searchList/{channelid}?_isJson=true`` JSON 单跳——
  列表响应直接带全文（无需二跳）；``manuscriptId`` 幂等、``url`` 协议相对须补 ``https:`` 前缀、
  ``publishedTime`` epoch ms（北京时间语义，``publishedTimeStr`` 字符串形态双保险）；
  翻页终止 = ``len(results) < _pageSize`` **或空数组**（rows 回显不作判据，Task 1 fix round
  钉死）+ ``ceil(total/pageSize)`` 页数兜底。

标题黑名单两栏（常量 ``POLICY_TITLE_BLACKLIST_COMMON`` 四站生效 + ``_BY_SOURCE`` 仅该站，
§5.5 常量为准）：标题级子串命中即丢弃 + ``last_warnings`` 计数；「出席」等词仅拦标题不拦正文
（防误杀政策解读文）。漏网由 LLM 兜底（isPolicy=false → LOW confidence 落库，T5 契约）。

输出列 ``POLICY_COLUMNS``（目标表 ``intelligence_policy_raw``，业务键 ``(source, external_id)``
UPSERT 幂等）；正文截 ``POLICY_CONTENT_MAX_CHARS``（8000 字）加「…[截断]」尾注供 LLM 感知，
列表层信息（标题/日期/URL）永不截断；容器缺失时全 body 文本兜底，兜底后仍短于
``POLICY_MIN_CONTENT_CHARS`` 视为壳页——单条丢弃 + 告警（不失败整轮）。

增量策略（沿 news 先例 D1）：conn_factory 查本源已存 external_id 集合，列表（新→旧）**首见
已存即停**；显式 ``params['start']/['end']``（backfill 意图）优先——跳过截断翻满 max_pages
（不做日期过滤，上游无日期参数）。supports_range=False：start/end 仅表达回补意图。

异常语义（fix round 1 细化）：请求级失败重试 ``POLICY_RETRY_ATTEMPTS`` 次后抛 ``SourceError``
走 selector 换源（不自吞）；列表页非空但零条目命中链接模式（结构漂移）→ ``SourceError``；
**单条**失败（缺日期/正文过短/详情或附件 404·410）丢弃并记 ``last_warnings``（不改终态）；
翻页页码 >1 的 404/410 = 上游静态存档/JSON 越界——**干净终止**（已采行保留正常提交），
首页 404 仍按请求失败显性告警。
"""

import contextlib
import datetime as dt
import json
import logging
import re
import time
from html.parser import HTMLParser
from urllib.parse import urlencode, urljoin
from urllib.request import Request, urlopen

import pandas as pd

from collector.sources.base import Source, SourceError
from collector.sources.constants import (
    POLICY_CONTENT_MAX_CHARS,
    POLICY_CSRC_LIST_URL,
    POLICY_CSRC_MAX_PAGES,
    POLICY_CSRC_PAGE_SIZE,
    POLICY_EXISTING_IDS_LIMIT,
    POLICY_HTTP_TIMEOUT,
    POLICY_LIST_URL_MOF,
    POLICY_LIST_URL_PBOC,
    POLICY_LIST_URL_STATS,
    POLICY_MIN_CONTENT_CHARS,
    POLICY_PAGE_INTERVAL,
    POLICY_RETRY_ATTEMPTS,
    POLICY_TITLE_BLACKLIST_BY_SOURCE,
    POLICY_TITLE_BLACKLIST_COMMON,
    POLICY_TRS_MAX_PAGES,
    POLICY_TRUNCATION_SUFFIX,
    POLICY_UA,
)

logger = logging.getLogger(__name__)

POLICY_COLUMNS = ["source", "external_id", "title", "url", "published_at", "content_text"]

# 北京时间无夏令时，固定 UTC+8 与 Asia/Shanghai 等价
_UTC8 = dt.timezone(dt.timedelta(hours=8))

_LIST_DATE = re.compile(r"(\d{4}-\d{2}-\d{2})")
_META_CREATE_DATE = re.compile(
    r"<meta[^>]+(?:name=[\"']createDate[\"'][^>]*content=[\"']([^\"']+)[\"']"
    r"|content=[\"']([^\"']+)[\"'][^>]*name=[\"']createDate[\"'])",
    re.IGNORECASE,
)
_PAGE_META_DATE_FMT = "%Y-%m-%d %H:%M:%S"

# 三站 trs 列表差异（探测报告 §5.1/§5.3/§5.4）：详情链接路径模式（external_id 捕获组）、
# 正文容器 id/class、静态翻页模板（首页即列表 URL 本身；TRS createPageHTML 第 2 页起
# index_{N-1}，pboc 为 11040-{N} 族，N 从 2 起）。
_TRS_SITE_CONFIG = {
    "pboc": {
        "detail_path": re.compile(r"/goutongjiaoliu/113456/113469/(\d{14,})/index\.html$"),
        "containers": ("zoom",),
        "page_url": lambda base, n: base if n == 1 else urljoin(base, f"11040-{n}.html"),
        "meta_date": True,  # 详情 meta createDate 精确发布时刻
    },
    "mof": {
        "detail_path": re.compile(r"/\d{6}/t(\d{8}_\d+)\.s?html?$"),  # 司局子域通用 TRS 模板
        "containers": ("TRS_Editor",),
        "page_url": lambda base, n: base if n == 1 else urljoin(base, f"index_{n - 1}.htm"),
        "meta_date": False,
    },
    "stats": {
        "detail_path": re.compile(r"/xw/tjxw/tzgg/\d{6}/t(\d{8}_\d+)\.s?html$"),
        "containers": ("txt-content",),
        "page_url": lambda base, n: base if n == 1 else urljoin(base, f"index_{n - 1}.html"),
        "meta_date": False,
    },
}


# ---------------------------------------------------------------- HTML 解析（标准库 html.parser，无新依赖）


class _PolicyListParser(HTMLParser):
    """列表页条目：``<a>``（href/title 属性/内文本）+ 其后紧邻的 ``<span>`` 日期（mof/stats/pboc 同构）。"""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.entries: list[dict] = []
        self._href: str | None = None
        self._title_attr = ""
        self._buf: list[str] = []
        # 最近一个未定日期的条目下标（数据节点日期归它）。不叫 _pending——撞 html.parser
        # 基类内部缓冲名（CPython 3.12 feed 即崩，3.13 已改名；CI 债 Item 1 教训）
        self._undated_idx = -1

    def handle_starttag(self, tag, attrs):
        if tag == "a":
            attr = dict(attrs)
            self._href = attr.get("href") or ""
            self._title_attr = attr.get("title") or ""
            self._buf = []

    def handle_endtag(self, tag):
        if tag == "a" and self._href is not None:
            self.entries.append(
                {"href": self._href, "title_attr": self._title_attr, "text": "".join(self._buf).strip(), "date": ""}
            )
            self._undated_idx = len(self.entries) - 1
            self._href = None

    def handle_data(self, data):
        if self._href is not None:
            self._buf.append(data)
        elif self._undated_idx >= 0 and not self.entries[self._undated_idx]["date"]:
            m = _LIST_DATE.search(data)
            if m:
                self.entries[self._undated_idx]["date"] = m.group(1)
                self._undated_idx = -1


class _ContentTextParser(HTMLParser):
    """详情正文：容器（id/class 任一命中）内的可见文本——跳过 style/script（内联 CSS 噪声）、
    块级标签分段（段落保留），容器缺失时可全 body 兜底（``capture_all=True``）。"""

    _SKIP = {"style", "script"}
    _BLOCK = {"p", "div", "br", "tr", "li", "td", "h1", "h2", "h3", "h4", "h5", "h6", "table"}

    def __init__(self, containers, capture_all=False):
        super().__init__(convert_charrefs=True)
        self._containers = set(containers)
        self._capture_all = capture_all
        self._depth = 1 if capture_all else 0  # capture_all：全 body 视为不可关闭的容器
        self._skip = 0
        self._chunks: list[str] = []

    def _match_div(self, attrs):
        attr = dict(attrs)
        ident = {attr.get("id") or "", *(attr.get("class") or "").split()}
        return bool(ident & self._containers)

    def handle_starttag(self, tag, attrs):
        if tag in self._SKIP:
            self._skip += 1
            return
        if self._skip:
            return
        if tag == "div":
            if self._capture_all:
                return  # 兜底模式不做 div 深度跟踪（永不关闭）
            if self._depth > 0:
                self._depth += 1
            elif self._match_div(attrs):
                self._depth = 1
        elif tag in self._BLOCK and self._depth:
            self._chunks.append("\n")

    def handle_endtag(self, tag):
        if tag in self._SKIP:
            if self._skip:
                self._skip -= 1
        elif tag == "div" and not self._capture_all and self._depth:
            self._depth -= 1

    def handle_data(self, data):
        if self._depth and not self._skip:
            self._chunks.append(data)

    def text(self):
        lines = [line.strip() for line in "".join(self._chunks).split("\n")]
        return "\n".join(line for line in lines if line)


def _extract_text(html, containers):
    """容器文本优先；容器缺失/为空 → 全 body 兜底（模板漂移单条软降级）。"""
    parser = _ContentTextParser(containers)
    try:
        parser.feed(html)
        parser.close()
    except Exception:
        return ""
    text = parser.text()
    if text:
        return text
    return _strip_html(html)


def _strip_html(html):
    """全文本剥标签（csrc JSON content 片段 / 容器兜底共用）；分段与容器模式一致。"""
    parser = _ContentTextParser((), capture_all=True)
    try:
        parser.feed(html)
        parser.close()
    except Exception:
        return re.sub(r"<[^>]+>", "", html or "")
    return parser.text()


def _truncate(text):
    if len(text) <= POLICY_CONTENT_MAX_CHARS:
        return text
    return text[:POLICY_CONTENT_MAX_CHARS] + POLICY_TRUNCATION_SUFFIX  # 尾注供 LLM 感知（§5.6）


# ---------------------------------------------------------------- 源实现


class PolicySiteSource(Source):
    """四部委政策站源：``PolicySiteSource(source_id, site=…, url=…, …)``。

    site ∈ {pboc, csrc, mof, stats}（DB source 列值同名字符串）；csrc 走 JSON 单跳，
    其余三站 TRS 同构两跳。参数化实例规格见模块级 ``POLICY_SOURCE_SPECS``。
    """

    supports_range = False  # 列表无日期过滤参数；params start/end 仅表达 backfill 意图（跳过已存截断）

    def __init__(
        self,
        source_id,
        conn_factory=None,
        *,
        site,
        url,
        page_size=POLICY_CSRC_PAGE_SIZE,
        max_pages=None,
        interval=POLICY_PAGE_INTERVAL,
        timeout=POLICY_HTTP_TIMEOUT,
        attempts=POLICY_RETRY_ATTEMPTS,
        sleep_fn=time.sleep,
    ):
        if site not in ("pboc", "csrc", "mof", "stats"):
            raise ValueError(f"site 须为 pboc/csrc/mof/stats 之一，收到 {site!r}")
        self.source_id = source_id
        self.conn_factory = conn_factory  # 增量截断用：查本源已存 external_id 集合
        self.site = site
        self.url = url
        self.page_size = page_size
        self.max_pages = (
            max_pages if max_pages is not None else (POLICY_CSRC_MAX_PAGES if site == "csrc" else POLICY_TRS_MAX_PAGES)
        )
        self.interval = interval
        self.timeout = timeout
        self.attempts = attempts
        self.sleep_fn = sleep_fn
        self._warnings: list[str] = []
        self._req_seq = 0
        self._dropped = 0  # 黑名单命中计数

    # ---------------- 请求

    def _polite(self):
        if self._req_seq > 0:
            self.sleep_fn(self.interval)
        self._req_seq += 1

    def _get(self, url, allow_missing=False):
        """单次 GET → str（utf-8 优先 gbk 兜底）/None。

        请求级异常重试 attempts 次后抛 SourceError（走 selector 换源）；``allow_missing`` 时
        404/410 不重试、返回 None——供翻页页码 >1（上游静态存档/JSON 越界有界，越界即干净
        终止、已采行保留）与详情页（条目级软失效，单条丢弃）使用；**首页**不得开
        allow_missing（源真不可达须显性失败）。
        """
        last_error = None
        for _attempt in range(1, self.attempts + 1):
            self._polite()
            try:
                with urlopen(Request(url, headers={"User-Agent": POLICY_UA}), timeout=self.timeout) as resp:
                    status = resp.status
                    body = resp.read()
                if status != 200:
                    if allow_missing and status in (404, 410):
                        return None
                    raise OSError(f"HTTP {status}")
                try:
                    return body.decode("utf-8")
                except UnicodeDecodeError:
                    return body.decode("gbk")
            except Exception as e:  # 请求级异常统一重试；结构漂移在解析层抛，不进此路径
                last_error = e
        raise SourceError(f"{self.source_id}: 连续 {self.attempts} 次请求/解析失败（{url}）：{last_error}")

    def _get_json(self, url, allow_missing=False):
        body = self._get(url, allow_missing=allow_missing)
        if body is None:
            return None
        try:
            return json.loads(body)
        except ValueError as e:
            raise SourceError(f"{self.source_id}: 响应非 JSON（{url}）：{e}") from e

    # ---------------- 增量与黑名单

    def _existing_ids(self):
        """本源已存 external_id 集合（按 source 过滤：四站 id 空间互不相交，跨站混查会假截断）。"""
        with self.conn_factory() as conn, conn.cursor() as cur:
            cur.execute(
                "SELECT external_id FROM intelligence_policy_raw WHERE source = %s ORDER BY id DESC LIMIT %s",
                (self.site, POLICY_EXISTING_IDS_LIMIT),
            )
            return {row[0] for row in cur.fetchall()}

    def _blacklist_hit(self, title):
        """标题黑名单两栏：通用栏四站 + 专属栏本站；返回命中词（未命中返回 None）。"""
        for keyword in POLICY_TITLE_BLACKLIST_COMMON:
            if keyword in title:
                return keyword
        for keyword in POLICY_TITLE_BLACKLIST_BY_SOURCE.get(self.site, ()):
            if keyword in title:
                return keyword
        return None

    # ---------------- csrc：JSON 单跳

    def _csrc_page_url(self, page):
        query = urlencode(
            {"_isAgg": "true", "_isJson": "true", "_pageSize": self.page_size, "_template": "index", "page": page}
        )
        return f"{POLICY_CSRC_LIST_URL}?{query}"

    def _fetch_csrc(self, existing):
        rows: list[dict] = []
        seen: set[str] = set()
        page = 1
        page_bound = None  # ceil(total/pageSize) 页数兜底
        while page <= self.max_pages:
            # 页码 >1 的 404/410 = 接口越界（正常路径是空数组，防上游改为 404）：干净终止
            payload = self._get_json(self._csrc_page_url(page), allow_missing=(page > 1))
            if payload is None:
                break
            data = payload.get("data") if isinstance(payload, dict) else None
            results = data.get("results") if isinstance(data, dict) else None
            if not isinstance(results, list):
                raise SourceError(f"{self.source_id}: 响应结构漂移（data.results 缺失/非列表），上游可能已变更")
            if page_bound is None and isinstance(data, dict) and isinstance(data.get("total"), int):
                page_bound = max(1, -(-data["total"] // self.page_size))
            if not results:
                break  # 越界页空数组（键不缺失、HTTP 仍 200，§5.2 实测）
            truncated = False
            for item in results:
                external_id = str(item.get("manuscriptId") or "").strip() if isinstance(item, dict) else ""
                title = str(item.get("title") or "").strip() if isinstance(item, dict) else ""
                if not external_id or not title:
                    self._warnings.append(f"条目缺 manuscriptId/title，已丢弃：{str(item)[:60]!r}")
                    continue
                keyword = self._blacklist_hit(title)
                if keyword:
                    self._dropped += 1
                    self._warnings.append(f"黑名单[{keyword}]丢弃：{title[:50]}")
                    continue
                if external_id in seen or (existing is not None and external_id in existing):
                    truncated = True  # 首见已存 manuscriptId 即停（§5.2）
                    break
                row = self._parse_csrc_item(item, external_id, title)
                if row is None:
                    continue
                rows.append(row)
                seen.add(external_id)
            if truncated:
                break
            if len(results) < self.page_size:
                break  # 末页短页（rows 回显不作判据，Task 1 fix round 钉死）
            if page_bound is not None and page >= page_bound:
                break  # ceil 兜底：服务端恒回满页时的页数上限
            page += 1
        return rows

    def _parse_csrc_item(self, item, external_id, title):
        url = str(item.get("url") or "").strip()
        if url.startswith("//"):
            url = f"https:{url}"  # 协议相对 URL（§5.2 实测）
        published = None
        try:
            published = dt.datetime.fromtimestamp(int(item.get("publishedTime")) / 1000, _UTC8)
        except (TypeError, ValueError, OSError, OverflowError):
            try:
                published = dt.datetime.strptime(str(item.get("publishedTimeStr")), _PAGE_META_DATE_FMT).replace(
                    tzinfo=_UTC8
                )
            except (TypeError, ValueError):
                self._warnings.append(f"{external_id} publishedTime/publishedTimeStr 均不可解析，已丢弃")
                return None
        content = _strip_html(str(item.get("content") or ""))
        return {
            "source": self.site,
            "external_id": external_id,
            "title": title,
            "url": url or None,
            "published_at": published,
            "content_text": _truncate(content) or None,
        }

    # ---------------- trs：两跳

    def _trs_page_url(self, page_no):
        return _TRS_SITE_CONFIG[self.site]["page_url"](self.url, page_no)

    def _trs_entries(self, html):
        parser = _PolicyListParser()
        try:
            parser.feed(html)
            parser.close()
        except Exception:
            return []
        pattern = _TRS_SITE_CONFIG[self.site]["detail_path"]
        entries: list[dict] = []
        index: dict[str, dict] = {}
        for entry in parser.entries:
            resolved = urljoin(self.url, entry["href"])
            m = pattern.search(resolved)
            if not m:
                continue
            external_id = m.group(1)
            dup = index.get(external_id)
            if dup is not None:
                # 同条目多终端副本（zxfb 同款模板 ×3）：<span> 日期跟在末个副本后，回填首副本
                if not dup["date"] and entry["date"]:
                    dup["date"] = entry["date"]
                continue
            item = {
                "external_id": external_id,
                "title": entry["title_attr"] or entry["text"],
                "url": resolved,
                "date": entry["date"],
            }
            index[external_id] = item
            entries.append(item)
        return entries

    def _parse_trs_item(self, item):
        """详情两跳：正文容器抓取 + published_at（pboc meta createDate 精确时刻，余为列表日 00:00）。"""
        if not _TRS_SITE_CONFIG[self.site]["meta_date"] and not item["date"]:
            # 无 meta 兜底的站点：列表无日期即无处取 published_at（NOT NULL）——零请求直接丢弃
            self._warnings.append(f"{item['external_id']} 无发布日期（列表 span 缺），已丢弃")
            return None
        # 详情 404/410 = 条目级软失效（附件被撤/链接腐化）：单条丢弃不失败整轮
        html = self._get(item["url"], allow_missing=True)
        if html is None:
            self._warnings.append(f"{item['external_id']} 详情页 404/410，已丢弃")
            return None
        published = None
        if _TRS_SITE_CONFIG[self.site]["meta_date"]:
            m = _META_CREATE_DATE.search(html)
            if m:
                raw = m.group(1) or m.group(2)
                # 格式异常不作硬失败：回落列表日期
                with contextlib.suppress(ValueError):
                    published = dt.datetime.strptime(raw, _PAGE_META_DATE_FMT).replace(tzinfo=_UTC8)
        if published is None:
            if not item["date"]:
                self._warnings.append(f"{item['external_id']} 无发布日期（列表 span/详情 meta 均缺），已丢弃")
                return None
            try:
                published = dt.datetime.strptime(item["date"], "%Y-%m-%d").replace(tzinfo=_UTC8)
            except ValueError:
                self._warnings.append(f"{item['external_id']} 日期不可解析：{item['date']!r}，已丢弃")
                return None
        text = _extract_text(html, _TRS_SITE_CONFIG[self.site]["containers"])
        if len(text) < POLICY_MIN_CONTENT_CHARS:
            # 容器与 body 兜底均过短：疑似抓到壳页/模板漂移——单条丢弃不失败整轮
            self._warnings.append(f"{item['external_id']} 正文过短（{len(text)} 字），已丢弃")
            return None
        return {
            "source": self.site,
            "external_id": item["external_id"],
            "title": item["title"],
            "url": item["url"],
            "published_at": published,
            "content_text": _truncate(text),
        }

    def _fetch_trs(self, params, existing):
        rows: list[dict] = []
        seen: set[str] = set()
        truncated = False
        for page_no in range(1, self.max_pages + 1):
            # 页码 >1 的 404/410 = 静态存档有界越界：干净终止（已采行保留）；首页 404 仍按请求失败
            html = self._get(self._trs_page_url(page_no), allow_missing=(page_no > 1))
            if html is None:
                break
            entries = self._trs_entries(html)
            if not entries:
                if page_no == 1:
                    raise SourceError(
                        f"{self.source_id}: 列表页零条目命中链接模式（结构漂移），上游可能已变更：{self.url}"
                    )
                break  # 静态翻页空页
            for item in entries:
                title = item["title"].strip()
                if not title:
                    self._warnings.append(f"{item['external_id']} 缺标题，已丢弃")
                    continue
                keyword = self._blacklist_hit(title)
                if keyword:
                    self._dropped += 1
                    self._warnings.append(f"黑名单[{keyword}]丢弃：{title[:50]}")
                    continue
                if item["external_id"] in seen or (existing is not None and item["external_id"] in existing):
                    truncated = True  # 首见已存即停（其后的更旧条目均已入库）
                    break
                row = self._parse_trs_item(item)
                if row is None:
                    continue
                rows.append(row)
                seen.add(item["external_id"])
            if truncated:
                break
        return rows

    # ---------------- 主流程

    def fetch(self, params):
        self.last_warnings = None  # 单例源：观测状态每次 fetch 重置
        self._warnings = []
        self._dropped = 0
        self._req_seq = 0
        # 显式 start/end（backfill 意图）优先于已存集合截断（沿 D1）
        incremental = not ("start" in params or "end" in params)
        existing = self._existing_ids() if incremental and self.conn_factory is not None else None
        rows = self._fetch_csrc(existing) if self.site == "csrc" else self._fetch_trs(params, existing)
        self._flush_warnings()
        return pd.DataFrame(rows, columns=POLICY_COLUMNS)

    def _flush_warnings(self):
        if self._dropped:
            self._warnings.insert(0, f"标题黑名单丢弃 {self._dropped} 条（LLM 兜底过滤，漏网标低置信）")
        if self._warnings:
            sample = "；".join(self._warnings[:5])
            self.last_warnings = [f"单条处理告警 {len(self._warnings)} 条，样例：{sample}"]
            logger.warning("%s: %s", self.source_id, self.last_warnings[0])


# ---------------------------------------------------------------- 装配规格（jobs.py 注册时展开为构造 kwargs）


POLICY_SOURCE_SPECS: dict[str, dict] = {
    "pboc": {"site": "pboc", "url": POLICY_LIST_URL_PBOC},
    "csrc": {"site": "csrc", "url": POLICY_CSRC_LIST_URL},
    "mof": {"site": "mof", "url": POLICY_LIST_URL_MOF},
    "stats": {"site": "stats", "url": POLICY_LIST_URL_STATS},
}
