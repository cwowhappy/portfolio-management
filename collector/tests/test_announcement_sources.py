"""MS-21 P2 Task 1 公告双源（巨潮 hisAnnouncement 主源 + 东财公告流降级源）。

样本形态取探测报告（09-调研报告/2026-09-29-MS20-数据源探测报告.md）§3 实测原文 +
§4 2026-09-29 P2 补测：
- 巨潮：POST form-encoded hisAnnouncement/query（announcements[]、announcementId 幂等、
  announcementTime epoch ms 披露日零点、adjunctUrl 拼 static.cninfo.com.cn）；orgId 经
  topSearch keyBoardList 解析（plate=sse/szse/bj 直接映射 column/plate）。
- 东财：GET np-anotice-stock api/security/ann（art_code 幂等、codes[]/columns[]/
  display_time 毫秒精度第三冒号、notice_date 日粒度窗口下界、pdf.dfcfw.com PDF 直链）。
覆盖口径（控制器裁定）：params['stocks'] 关注集标的全量采集；全市场仅 6 类直判栏目
（ANNOUNCEMENT_MAJOR_COLUMNS 并集 10 category × 3 市场）major=true 条目；增持/减持/
回购/关联交易 4 类无专属栏目不做全市场扫描。增量 = 整页已存截断（同日内 id 乱序实测，
不可首见即停）+ 分页上限；请求级失败重试 3 次后 SourceError 走 selector 降级。
"""

import datetime as dt
import json
import urllib.error
import urllib.parse
from unittest.mock import MagicMock

import pytest

import collector.sources.announcements as ann
from collector.sources.announcements import ANN_COLUMNS, _watchlist_stocks
from collector.sources.base import SourceError
from collector.sources.constants import (
    ANN_EXISTING_IDS_LIMIT,
    ANN_PAGE_INTERVAL,
    ANNOUNCEMENT_MAJOR_COLUMNS,
    CNINFO_MARKETS,
)

_UTC8 = dt.timezone(dt.timedelta(hours=8))
_CN_TIME_MS = 1790611200000  # 2026-09-29 00:00:00+08:00（§3.2 样本：披露日北京时间零点）
_CN_PUBLISHED = dt.datetime(2026, 9, 29, 0, 0, tzinfo=_UTC8)
_EM_PUBLISHED = dt.datetime(2026, 9, 29, 20, 37, 8, tzinfo=_UTC8)


# ---------------------------------------------------------------- 测试基座


def _resp(payload, status=200):
    """urlopen 返回的 HTTPResponse 形状 mock（上下文管理器 + read + status）。"""
    body = payload if isinstance(payload, bytes) else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    r = MagicMock()
    r.status = status
    r.read.return_value = body
    r.__enter__.return_value = r
    r.__exit__.return_value = False
    return r


class _ExistingCursor:
    def __init__(self, rows):
        self._rows = rows
        self.sql = None
        self.params = None

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def execute(self, sql, *args, **kwargs):
        self.sql = sql
        self.params = args[0] if args else None

    def fetchall(self):
        return self._rows


class _ExistingConn:
    """已存集合 conn_factory 假对象：fetchall → [(external_id,), ...]；游标共享便于断言 SQL。"""

    def __init__(self, rows):
        self._cursor = _ExistingCursor(rows)

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def cursor(self):
        return self._cursor


def _existing_factory(ids):
    conn = _ExistingConn([(i,) for i in ids])
    return lambda: conn


def _no_sleep(_seconds):
    pass


class _FixedClock:
    """now_fn 固定 2026-09-29 12:00+08:00：默认窗口断言确定性。"""

    def __call__(self):
        return dt.datetime(2026, 9, 29, 12, 0, tzinfo=_UTC8)


def _cn_item(
    ann_id,
    sec_code="002201",
    sec_name="九鼎新材",
    title="关于修订《公司章程》及相应制度的公告",
    ann_type="01010503||010112||012301",
    time_ms=_CN_TIME_MS,
    adjunct="finalpage/2026-09-29/1225586247.PDF",
    **extra,
):
    item = {
        "secCode": sec_code,
        "secName": sec_name,
        "announcementId": ann_id,
        "announcementTitle": title,
        "announcementTime": time_ms,
        "adjunctUrl": adjunct,
        "announcementType": ann_type,
    }
    item.update(extra)
    return item


def _cn_page(anns, has_more=False, total=None):
    """anns=None 表示实测空结果形态（announcements 键缺失 + totalRecordNum=0）。"""
    payload = {
        "classifiedAnnouncements": None,
        "totalSecurities": 0,
        "totalAnnouncement": len(anns) if anns is not None else 0,
        "hasMore": has_more,
        "totalpages": 0,
    }
    if total is not None:
        payload["totalRecordNum"] = total
    if anns is not None:
        payload["announcements"] = anns
    return payload


class _CninfoRouter:
    """按请求体路由响应的 urlopen 假对象：记录查询计划，逐 (stock|column,category) 按页序消费。

    - topSearch 请求（URL 含 topSearch）：keyWord → topsearch[keyWord]（缺省空 keyBoardList）。
    - hisAnnouncement 请求：stock 非空走 stock_pages[stock]，否则 category_pages[(column, category)]；
      pageNum 超出提供的页列表时回空页（用于断言「未再翻页」）。
    """

    def __init__(self, category_pages=None, stock_pages=None, topsearch=None):
        self.category_pages = category_pages or {}
        self.stock_pages = stock_pages or {}
        self.topsearch = topsearch or {}
        self.calls = []
        self.topsearch_calls = []

    def __call__(self, req, timeout=None):
        form = {k: v[0] for k, v in urllib.parse.parse_qs(req.data.decode("utf-8"), keep_blank_values=True).items()}
        if "topSearch" in req.full_url:
            self.topsearch_calls.append(form)
            return _resp(self.topsearch.get(form.get("keyWord"), {"keyBoardList": []}))
        self.calls.append(form)
        if form.get("stock"):
            pages = self.stock_pages.get(form["stock"])
        else:
            pages = self.category_pages.get((form.get("column"), form.get("category")))
        page_no = int(form.get("pageNum", 1))
        if pages and page_no <= len(pages):
            return _resp(pages[page_no - 1])
        return _resp(_cn_page(None))


def _em_ann_item(
    art_code,
    stock_code="002960",
    short_name="青鸟智控",
    column_name="召开股东大会通知",
    display_time="2026-09-29 20:37:08:446",
    notice_date="2026-09-30 00:00:00",
    title=None,
    columns=None,
    codes=None,
    **extra,
):
    if codes is None:
        codes = [{"ann_type": "A,SZA", "market_code": "0", "short_name": short_name, "stock_code": stock_code}]
    if columns is None:
        columns = [{"column_code": "001002003001002", "column_name": column_name}]
    item = {
        "art_code": art_code,
        "codes": codes,
        "columns": columns,
        "display_time": display_time,
        "notice_date": notice_date,
        "title": title if title is not None else f"{short_name}:测试公告",
        "title_ch": "",
        "title_en": "",
        "sort_date": "2026-09-30 12:00:00",
        "eiTime": "2026-09-29 20:38:13:000",
    }
    item.update(extra)
    return item


def _em_ann_page(items, total_hits=50000):
    return {
        "success": 1,
        "error": "",
        "data": {"list": items, "page_index": 1, "page_size": len(items), "total_hits": total_hits},
    }


class _EastmoneyRouter:
    """按 URL 参数路由的 urlopen 假对象：key=stock_list（""=全市场），页序消费 page_index。"""

    def __init__(self, pages_by_stock):
        self.pages_by_stock = pages_by_stock
        self.calls = []

    def __call__(self, req, timeout=None):
        parsed = urllib.parse.parse_qs(urllib.parse.urlsplit(req.full_url).query, keep_blank_values=True)
        query = {k: v[0] for k, v in parsed.items()}
        self.calls.append(query)
        pages = self.pages_by_stock.get(query.get("stock_list", ""), [])
        page_no = int(query.get("page_index", 1))
        if page_no <= len(pages):
            return _resp(pages[page_no - 1])
        return _resp(_em_ann_page([]))


def _cn_source(conn_factory=None, **kwargs):
    kwargs.setdefault("sleep_fn", _no_sleep)
    kwargs.setdefault("now_fn", _FixedClock())
    return ann.CninfoAnnouncementSource("cninfo_ann", conn_factory=conn_factory, **kwargs)


def _em_source(conn_factory=None, **kwargs):
    kwargs.setdefault("sleep_fn", _no_sleep)
    kwargs.setdefault("now_fn", _FixedClock())
    return ann.EastmoneyAnnouncementSource("eastmoney_ann", conn_factory=conn_factory, **kwargs)


# ---------------------------------------------------------------- 栏目映射锚定（探测报告 §3.4）


def test_announcement_major_columns_anchor():
    """6 类直判映射锚定：并集 10 个 category（§3.4 矩阵；「9 个」为报告原文误计），
    增持/减持/回购/关联交易 4 类与宽栏目 gqbd/rcjy 刻意缺席（控制器裁定不做全市场扫描）。"""
    expected = {
        "PERIODIC_REPORT": {"category_ndbg_szsh", "category_bndbg_szsh", "category_yjdbg_szsh", "category_sjdbg_szsh"},
        "EARNINGS_FORECAST": {"category_yjygjxz_szsh"},  # 栏目混含业绩快报（§3.4）
        "EARNINGS_FLASH": {"category_yjygjxz_szsh"},
        "PLACEMENT": {"category_zf_szsh", "category_pg_szsh"},
        "EQUITY_INCENTIVE": {"category_gqjl_szsh"},
        "DELISTING_RISK": {"category_tbclts_szsh", "category_tszlq_szsh"},
    }
    assert expected == ANNOUNCEMENT_MAJOR_COLUMNS
    assert "INCREASE_HOLD" not in ANNOUNCEMENT_MAJOR_COLUMNS
    assert "DECREASE_HOLD" not in ANNOUNCEMENT_MAJOR_COLUMNS
    assert "BUYBACK" not in ANNOUNCEMENT_MAJOR_COLUMNS
    assert "RELATED_TRANSACTION" not in ANNOUNCEMENT_MAJOR_COLUMNS
    # 扫描栏目并集 = 10（报告 §3.4 建议「9 个」系原文计数误差，实现以矩阵并集为准）
    union = set().union(*ANNOUNCEMENT_MAJOR_COLUMNS.values())
    assert len(union) == 10
    assert "category_gqbd_szsh" not in union and "category_rcjy_szsh" not in union


# ---------------------------------------------------------------- 关注集注入（params/env，collector 不查业务表）


def test_watchlist_stocks_from_params_list_and_csv():
    """params['stocks'] 支持 list 与逗号串；去重保序、空白容错。"""
    assert _watchlist_stocks({"stocks": ["000858", "600519"]}) == ["000858", "600519"]
    assert _watchlist_stocks({"stocks": "000858, 600519,,000858"}) == ["000858", "600519"]


def test_watchlist_stocks_env_indirection(monkeypatch):
    """params['stocks_env'] 指定 env 变量名；无 params 时回落默认 ANN_STOCKS_ENV。"""
    monkeypatch.setenv("MY_ANN_STOCKS", "000858,600519")
    assert _watchlist_stocks({"stocks_env": "MY_ANN_STOCKS"}) == ["000858", "600519"]
    monkeypatch.setenv("INTELLIGENCE_ANN_STOCKS", "920982")
    assert _watchlist_stocks({}) == ["920982"]
    monkeypatch.delenv("INTELLIGENCE_ANN_STOCKS")
    assert _watchlist_stocks({}) == []


# ---------------------------------------------------------------- 巨潮主源


def test_cninfo_category_scan_plan_and_major_rows(mocker):
    """全市场扫描：10 直判栏目 × 3 市场查询计划钉死；命中栏目条目 major=true 全字段映射。"""
    ndbg = _cn_item("1225586247", ann_type="01010503||010112||010303", title="九鼎新材2025年年度报告")
    yjyg = _cn_item("1225586248", sec_code="600519", sec_name="贵州茅台", ann_type="012111", title="业绩预告")
    router = _CninfoRouter(
        category_pages={
            ("szse", "category_ndbg_szsh"): [_cn_page([ndbg])],
            ("sse", "category_yjygjxz_szsh"): [_cn_page([yjyg])],
        }
    )
    mocker.patch.object(ann, "urlopen", side_effect=router)
    src = _cn_source()
    df = src.fetch({})
    assert list(df.columns) == ANN_COLUMNS
    assert list(df["external_id"]) == ["1225586247", "1225586248"]
    row = df.iloc[0]
    assert row["source"] == "cninfo"
    assert row["stock_code"] == "002201" and row["stock_name"] == "九鼎新材"
    assert row["title"] == "九鼎新材2025年年度报告"
    assert row["ann_type_source"] == "01010503||010112||010303"  # §3.3：分类码链原样入库
    assert bool(row["major"]) is True  # 栏目直判（ndbg → PERIODIC_REPORT）
    assert row["published_at"] == _CN_PUBLISHED
    assert row["pdf_url"] == "https://static.cninfo.com.cn/finalpage/2026-09-29/1225586247.PDF"
    # 查询计划：恰好 10 category × 3 市场；增减持宽栏目（gqbd/rcjy）不被扫描
    plan = {(c["column"], c["category"]) for c in router.calls if not c.get("stock")}
    expected = {(col, cat) for col in CNINFO_MARKETS for cat in set().union(*ANNOUNCEMENT_MAJOR_COLUMNS.values())}
    assert plan == expected
    assert all(c.get("category") != "category_gqbd_szsh" for c in router.calls)
    assert all(c.get("category") != "category_rcjy_szsh" for c in router.calls)
    # 请求形态：POST form-encoded + seDate 默认窗口（now_fn 固定 09-29 → 回看 3 天）
    first = router.calls[0]
    assert first["tabName"] == "fulltext" and first["pageNum"] == "1"
    assert first["seDate"] == "2026-09-26~2026-09-29"


def test_cninfo_watchlist_full_collection_and_dedup(mocker):
    """关注集标的全量采集（所有栏目）：topSearch 解析 orgId/市场 → 逐股无 category 查询；
    与栏目扫描重复的 announcementId 不重复输出；非直判栏目条目 major=false 保留。"""
    major_item = _cn_item("A1", ann_type="010301", title="2025年年度报告")
    minor_item = _cn_item("A2", ann_type="01010503||010112||011513", title="关于回购公司股份进展的公告")
    # 联想结果混有他股（须精确 code 匹配）与目标股
    topsearch_hit = {
        "keyBoardList": [
            {"code": "000999", "orgId": "gssz0000999", "plate": "szse", "zwjc": "他股"},
            {"code": "000858", "orgId": "gssz0000858", "plate": "szse", "zwjc": "五粮液"},
        ]
    }
    router = _CninfoRouter(
        category_pages={("szse", "category_ndbg_szsh"): [_cn_page([major_item])]},
        stock_pages={"000858,gssz0000858": [_cn_page([major_item, minor_item])]},
        topsearch={"000858": topsearch_hit},
    )
    mocker.patch.object(ann, "urlopen", side_effect=router)
    src = _cn_source()
    df = src.fetch({"stocks": ["000858"]})
    # A1 仅输出一次（栏目扫描 major=true 版本），A2 关注集全量保留（major=false）
    assert list(df["external_id"]) == ["A1", "A2"]
    assert bool(df.iloc[0]["major"]) is True and bool(df.iloc[1]["major"]) is False
    stock_calls = [c for c in router.calls if c.get("stock")]
    assert len(stock_calls) == 1
    assert stock_calls[0]["stock"] == "000858,gssz0000858"
    assert stock_calls[0]["column"] == "szse" and stock_calls[0]["plate"] == "sz"
    assert stock_calls[0]["category"] == ""  # 关注集不按栏目过滤（全量）
    assert len(router.topsearch_calls) == 1


def test_cninfo_watchlist_market_from_plate(mocker):
    """沪股标的：topSearch plate=sse → column=sse&plate=sh（§3.1：配错静默 0 条）。"""
    router = _CninfoRouter(
        stock_pages={"600519,gssh0600519": [_cn_page([_cn_item("M1", sec_code="600519")])]},
        topsearch={"600519": {"keyBoardList": [{"code": "600519", "orgId": "gssh0600519", "plate": "sse"}]}},
    )
    mocker.patch.object(ann, "urlopen", side_effect=router)
    df = _cn_source().fetch({"stocks": ["600519"]})
    assert list(df["external_id"]) == ["M1"]
    stock_call = next(c for c in router.calls if c.get("stock"))
    assert stock_call["column"] == "sse" and stock_call["plate"] == "sh"


def test_cninfo_orgid_cached_across_fetches(mocker):
    """orgId 进程内缓存：两次 fetch 只打一次 topSearch（orgId 长期稳定，§3.1 采集侧建议）。"""
    router = _CninfoRouter(
        stock_pages={"000858,gssz0000858": [_cn_page([_cn_item("A2")])]},
        topsearch={"000858": {"keyBoardList": [{"code": "000858", "orgId": "gssz0000858", "plate": "szse"}]}},
    )
    mocker.patch.object(ann, "urlopen", side_effect=router)
    src = _cn_source()
    src.fetch({"stocks": ["000858"]})
    src.fetch({"stocks": ["000858"]})
    assert len(router.topsearch_calls) == 1


def test_cninfo_orgid_unresolved_skips_stock_with_warning(mocker):
    """topSearch 无命中（错码/退市）：该标的跳过 + last_warnings，全市场扫描不受影响。"""
    router = _CninfoRouter(
        category_pages={("szse", "category_ndbg_szsh"): [_cn_page([_cn_item("A1")])]},
        topsearch={"000858": {"keyBoardList": []}},
    )
    mocker.patch.object(ann, "urlopen", side_effect=router)
    src = _cn_source()
    df = src.fetch({"stocks": ["000858"]})
    assert list(df["external_id"]) == ["A1"]  # 全市场扫描照常
    assert src.last_warnings and "000858" in src.last_warnings[0]
    assert not any(c.get("stock") for c in router.calls)


def test_cninfo_topsearch_failure_soft_skips_stock(mocker):
    """topSearch 请求失败（重试耗尽）软降级：跳过该标的 + last_warnings，不中断 fetch——
    该标的 6 类直判条目仍由全市场栏目扫描覆盖。"""
    router = _CninfoRouter(category_pages={("szse", "category_ndbg_szsh"): [_cn_page([_cn_item("A1")])]})

    def _flaky(req, timeout=None):
        if "topSearch" in req.full_url:
            raise urllib.error.URLError("down")
        return router(req, timeout)

    mocker.patch.object(ann, "urlopen", side_effect=_flaky)
    src = _cn_source(sleep_fn=_no_sleep)
    df = src.fetch({"stocks": ["000858"]})
    assert list(df["external_id"]) == ["A1"]
    assert src.last_warnings and "000858" in src.last_warnings[0]
    assert not any(c.get("stock") for c in router.calls)


def test_cninfo_bad_date_param_source_error(mocker):
    """params.start/end 不可解析 → 显性 SourceError（配置错误不静默回落默认窗口）。"""
    mocker.patch.object(ann, "urlopen", side_effect=_CninfoRouter())
    src = _cn_source()
    with pytest.raises(SourceError, match="params.end"):
        src.fetch({"end": "2026-13-99"})


def test_cninfo_non_dict_payload_drift(mocker):
    """响应体非 JSON 对象（如被网关换成数组/纯文本）→ 结构漂移 SourceError。"""
    mocker.patch.object(ann, "urlopen", return_value=_resp(["not", "a", "dict"]))
    src = _cn_source()
    with pytest.raises(SourceError, match="结构漂移"):
        src.fetch({})


def test_cninfo_page_level_truncation(mocker):
    """增量截断按整页判定（P2 补测：全市场查询同日内 announcementId 不保序，首见即停会漏）：
    混合页（含新条目）继续翻，整页已存才停；已存条目不重复输出。"""
    p1 = _cn_page([_cn_item("N1"), _cn_item("S1"), _cn_item("N2")], has_more=True, total=973)
    p2 = _cn_page([_cn_item("S2"), _cn_item("S3")], has_more=True, total=973)
    router = _CninfoRouter(category_pages={("szse", "category_ndbg_szsh"): [p1, p2]})
    mocker.patch.object(ann, "urlopen", side_effect=router)
    src = _cn_source(conn_factory=_existing_factory(["S1", "S2", "S3"]))
    df = src.fetch({})
    assert list(df["external_id"]) == ["N1", "N2"]  # 已存 S* 不重复输出
    stream_calls = [c for c in router.calls if (c["column"], c["category"]) == ("szse", "category_ndbg_szsh")]
    assert len(stream_calls) == 2  # 第 2 页整页已存 → 停，不翻第 3 页


def test_cninfo_explicit_range_skips_truncation(mocker):
    """显式 params start/end（backfill 意图）：seDate 取参数窗口 + 跳过已存截断（D1 同款）。"""
    page = _cn_page([_cn_item("S1"), _cn_item("N1")])
    router = _CninfoRouter(category_pages={("szse", "category_ndbg_szsh"): [page]})
    mocker.patch.object(ann, "urlopen", side_effect=router)
    src = _cn_source(conn_factory=_existing_factory(["S1"]))
    df = src.fetch({"start": "2026-08-01", "end": "2026-08-31"})
    assert list(df["external_id"]) == ["S1", "N1"]  # 已存 S1 不截断
    sedates = {c["seDate"] for c in router.calls}
    assert sedates == {"2026-08-01~2026-08-31"}


def test_cninfo_http_error_retries_three_then_source_error(mocker):
    """请求级异常重试 ANN_RETRY_ATTEMPTS 次后抛 SourceError 走 selector 降级（不自吞）。"""
    m = mocker.patch.object(ann, "urlopen", side_effect=urllib.error.URLError("boom"))
    sleeps = []
    src = _cn_source(sleep_fn=sleeps.append)
    with pytest.raises(SourceError, match="连续 3 次"):
        src.fetch({})
    assert m.call_count == 3
    assert sleeps == [ANN_PAGE_INTERVAL, ANN_PAGE_INTERVAL]


def test_cninfo_non_200_status_source_error(mocker):
    """HTTP 非 200 → 重试耗尽后 SourceError。"""
    mocker.patch.object(ann, "urlopen", return_value=_resp({}, status=502))
    src = _cn_source(attempts=1)
    with pytest.raises(SourceError, match="502"):
        src.fetch({})


def test_cninfo_structural_drift_source_error(mocker):
    """响应 200 但结构漂移（announcements 非列表 / 有总数却缺键）→ 直接 SourceError 不重试。"""
    m = mocker.patch.object(ann, "urlopen", return_value=_resp({"announcements": "漂移"}))
    src = _cn_source()
    with pytest.raises(SourceError, match="结构漂移"):
        src.fetch({})
    assert m.call_count == 1  # 结构漂移不进重试路径


def test_cninfo_total_without_announcements_is_drift(mocker):
    """totalRecordNum>0 却无 announcements 键：正常空结果是 totalRecordNum=0（P2 补测实测），
    有总数无条目视为上游漂移。"""
    m = mocker.patch.object(ann, "urlopen", return_value=_resp({"totalRecordNum": 973, "hasMore": True}))
    src = _cn_source()
    with pytest.raises(SourceError, match="结构漂移"):
        src.fetch({})
    assert m.call_count == 1


def test_cninfo_item_drop_on_missing_fields_and_warns(mocker):
    """单条缺 announcementId/title/secCode/announcementTime → 丢弃 + last_warnings；好条目不受影响。"""
    items = [
        _cn_item("bad1", title=""),
        _cn_item("bad2", sec_code=""),
        _cn_item("bad3", time_ms=None),
        {"announcementTitle": "无 id 条目"},  # 缺 announcementId
        _cn_item("good"),
    ]
    router = _CninfoRouter(category_pages={("szse", "category_ndbg_szsh"): [_cn_page(items)]})
    mocker.patch.object(ann, "urlopen", side_effect=router)
    src = _cn_source()
    df = src.fetch({})
    assert list(df["external_id"]) == ["good"]
    assert src.last_warnings and len(src.last_warnings[0]) > 0
    assert "bad1" in src.last_warnings[0] and "bad3" in src.last_warnings[0]


def test_cninfo_empty_streams_return_empty_frame(mocker):
    """全部栏目空结果（实测空页形态：announcements 键缺失）→ 0 行但列契约保持。"""
    router = _CninfoRouter()
    mocker.patch.object(ann, "urlopen", side_effect=router)
    df = _cn_source().fetch({})
    assert df.empty and list(df.columns) == ANN_COLUMNS


def test_cninfo_existing_ids_queries_own_source(mocker):
    """已存集合按本源 source 过滤（业务键 (source, external_id)）：巨潮/东财 id 空间不相交，
    跨源混查会让备源首跑永远截不断。"""
    factory = _existing_factory(["x"])
    src = _cn_source(conn_factory=factory)
    src._existing_ids()
    conn = factory()
    with conn.cursor() as cur:
        assert "intelligence_announcement" in cur.sql
        assert "source = %s" in cur.sql
        assert cur.params == ("cninfo", ANN_EXISTING_IDS_LIMIT)


# ---------------------------------------------------------------- 东财降级源


def test_eastmoney_full_market_major_filter(mocker):
    """全市场流：仅 6 类直判 column_name 命中输出（major=true）；回购/关联交易等
    4 类与未映射栏目全市场不输出（控制器裁定）；display_time 毫秒段剥离；PDF 直链拼接。"""
    items = [
        _em_ann_item("AN1", column_name="半年度报告摘要", title="茅台:2026年半年度报告摘要"),
        _em_ann_item("AN2", column_name="业绩预告", title="某公司:业绩预告"),
        _em_ann_item("AN3", column_name="董事会决议公告"),
        _em_ann_item("AN4", column_name="回购实施公告"),  # BUYBACK：无巨潮专属栏目 → 全市场不采
        _em_ann_item("AN5", column_name="关联交易"),  # RELATED_TRANSACTION：同上
    ]
    router = _EastmoneyRouter({"": [_em_ann_page(items)]})
    mocker.patch.object(ann, "urlopen", side_effect=router)
    src = _em_source()
    df = src.fetch({})
    assert list(df.columns) == ANN_COLUMNS
    assert list(df["external_id"]) == ["AN1", "AN2"]
    row = df.iloc[0]
    assert row["source"] == "eastmoney_ann"
    assert row["stock_code"] == "002960" and row["stock_name"] == "青鸟智控"
    assert row["ann_type_source"] == "半年度报告摘要"
    assert bool(row["major"]) is True
    assert row["published_at"] == _EM_PUBLISHED  # 毫秒段 ":446" 剥离
    assert row["pdf_url"] == "https://pdf.dfcfw.com/pdf/H2_AN1_1.pdf"
    # 全市场请求形态：无 stock_list + f_node=0 + ann_type=A + sr=-1
    q = router.calls[0]
    assert q["stock_list"] == "" and q["f_node"] == "0" and q["ann_type"] == "A" and q["sr"] == "-1"


def test_eastmoney_watchlist_full_collection_and_dedup(mocker):
    """关注集（stock_list 多股一次流）：全量采集不按栏目过滤；与全市场流重复的
    art_code 不重复输出；未命中直判栏目的条目 major=false 保留。"""
    market_major = _em_ann_item("AN1", column_name="业绩预告")
    dup = _em_ann_item("AN1", column_name="业绩预告")
    minor = _em_ann_item("AN2", column_name="董事会决议公告")
    router = _EastmoneyRouter(
        {
            "": [_em_ann_page([market_major])],
            "600519,000858": [_em_ann_page([dup, minor])],
        }
    )
    mocker.patch.object(ann, "urlopen", side_effect=router)
    src = _em_source()
    df = src.fetch({"stocks": ["600519", "000858"]})
    assert list(df["external_id"]) == ["AN1", "AN2"]
    assert bool(df.iloc[0]["major"]) is True and bool(df.iloc[1]["major"]) is False
    watch_calls = [c for c in router.calls if c["stock_list"]]
    # 接口无 hasMore 信号：翻至空页停（页 1 有产出、页 2 空）
    assert [c["page_index"] for c in watch_calls] == ["1", "2"]
    assert all(c["stock_list"] == "600519,000858" for c in watch_calls)


def test_eastmoney_window_floor_stops_paging(mocker):
    """窗口下界：notice_date < start（日粒度严格倒序）即停整条流，下界外条目不输出、不再翻页。"""
    in1 = _em_ann_item("AN1", column_name="业绩预告", notice_date="2026-09-28 00:00:00")
    in2 = _em_ann_item("AN2", column_name="业绩预告", notice_date="2026-09-27 00:00:00")
    out1 = _em_ann_item("AN3", column_name="业绩预告", notice_date="2026-09-25 00:00:00")  # 早于默认窗口起点 09-26
    out2 = _em_ann_item("AN4", column_name="业绩预告", notice_date="2026-09-24 00:00:00")
    router = _EastmoneyRouter({"": [_em_ann_page([in1, in2]), _em_ann_page([out1, out2])]})
    mocker.patch.object(ann, "urlopen", side_effect=router)
    src = _em_source()
    df = src.fetch({})
    assert list(df["external_id"]) == ["AN1", "AN2"]
    assert len(router.calls) == 2  # 第 2 页首条低于下界 → 停，不翻第 3 页


def test_eastmoney_page_overlap_dedup(mocker):
    """页间重叠（P2 补测实测 4/50：活跃时段头部插入致窗口漂移）：run 级 art_code 去重。"""
    a = _em_ann_item("A", column_name="业绩预告")
    b = _em_ann_item("B", column_name="业绩预告")
    c = _em_ann_item("C", column_name="业绩预告")
    router = _EastmoneyRouter({"": [_em_ann_page([a, b]), _em_ann_page([b, c])]})
    mocker.patch.object(ann, "urlopen", side_effect=router)
    df = _em_source().fetch({})
    assert list(df["external_id"]) == ["A", "B", "C"]


def test_eastmoney_page_level_truncation(mocker):
    """整页已存截断：混合页继续翻，整页已存停；已存条目不重复输出。"""
    n1 = _em_ann_item("N1", column_name="业绩预告")
    s1, s2, s3 = _em_ann_item("S1"), _em_ann_item("S2"), _em_ann_item("S3")
    router = _EastmoneyRouter({"": [_em_ann_page([n1, s1]), _em_ann_page([s2, s3])]})
    mocker.patch.object(ann, "urlopen", side_effect=router)
    src = _em_source(conn_factory=_existing_factory(["S1", "S2", "S3"]))
    df = src.fetch({})
    assert list(df["external_id"]) == ["N1"]
    assert len(router.calls) == 2


def test_eastmoney_structural_drift_and_success_flag(mocker):
    """success!=1 → 接口不可用；success=1 但 data.list 缺失 → 结构漂移；均 SourceError 不重试。"""
    m = mocker.patch.object(ann, "urlopen", return_value=_resp({"success": 0, "error": "bad", "data": {"list": []}}))
    src = _em_source()
    with pytest.raises(SourceError, match="success"):
        src.fetch({})
    assert m.call_count == 1
    m2 = mocker.patch.object(ann, "urlopen", return_value=_resp({"success": 1, "data": {}}))
    with pytest.raises(SourceError, match="结构漂移"):
        src.fetch({})
    assert m2.call_count == 1


def test_eastmoney_http_error_retries_then_source_error(mocker):
    """请求级失败重试 3 次后 SourceError（selector 降级路径）。"""
    m = mocker.patch.object(ann, "urlopen", side_effect=urllib.error.URLError("boom"))
    src = _em_source(sleep_fn=_no_sleep)
    with pytest.raises(SourceError, match="连续 3 次"):
        src.fetch({})
    assert m.call_count == 3


def test_eastmoney_time_fallback_and_multi_columns(mocker):
    """display_time 缺失/不可解析回落 notice_date；多栏目 column_name 以 || 连接（对齐巨潮码链风格）。"""
    no_display = _em_ann_item(
        "AN1",
        display_time=None,
        notice_date="2026-09-28 00:00:00",
        columns=[{"column_name": "半年度报告全文"}, {"column_name": "半年度报告摘要"}],
    )
    bad_format = _em_ann_item("AN3", display_time="不是时间", notice_date="2026-09-28 00:00:00", column_name="业绩预告")
    no_both = _em_ann_item("AN2", display_time=None, notice_date=None)
    router = _EastmoneyRouter({"": [_em_ann_page([no_display, bad_format, no_both])]})
    mocker.patch.object(ann, "urlopen", side_effect=router)
    src = _em_source()
    df = src.fetch({})
    assert list(df["external_id"]) == ["AN1", "AN3"]
    assert df.iloc[0]["published_at"] == dt.datetime(2026, 9, 28, 0, 0, tzinfo=_UTC8)
    assert df.iloc[0]["ann_type_source"] == "半年度报告全文||半年度报告摘要"
    assert df.iloc[1]["published_at"] == dt.datetime(2026, 9, 28, 0, 0, tzinfo=_UTC8)  # 坏格式回落 notice_date
    assert src.last_warnings and "AN2" in src.last_warnings[0]


def test_eastmoney_multi_code_item_uses_first(mocker):
    """多标的公告（如增减持双方）取 codes[0]（业务键 (source, external_id) 单行约束）。"""
    item = _em_ann_item(
        "AN1",
        codes=[
            {"stock_code": "000858", "short_name": "五粮液"},
            {"stock_code": "600519", "short_name": "贵州茅台"},
        ],
        column_name="业绩预告",
    )
    router = _EastmoneyRouter({"": [_em_ann_page([item])]})
    mocker.patch.object(ann, "urlopen", side_effect=router)
    df = _em_source().fetch({})
    assert df.iloc[0]["stock_code"] == "000858" and df.iloc[0]["stock_name"] == "五粮液"


def test_eastmoney_watchlist_missing_fields_dropped_with_warning(mocker):
    """单条缺 codes/缺 title/缺 art_code → 丢弃 + last_warnings（title/stock_code NOT NULL）。"""
    no_codes = _em_ann_item("AN1", codes=[], column_name="业绩预告")
    no_title = _em_ann_item("AN2", title="", column_name="业绩预告")
    no_art = _em_ann_item("", column_name="业绩预告")
    router = _EastmoneyRouter({"600519": [_em_ann_page([no_codes, no_title, no_art])]})
    mocker.patch.object(ann, "urlopen", side_effect=router)
    src = _em_source()
    df = src.fetch({"stocks": ["600519"]})
    assert df.empty and list(df.columns) == ANN_COLUMNS
    assert src.last_warnings and "AN1" in src.last_warnings[0] and "AN2" in src.last_warnings[0]
    assert "art_code" in src.last_warnings[0]


def test_eastmoney_empty_page_returns_empty_frame(mocker):
    """空页 → 0 行但列契约保持（executor/writer 0 行路径）。"""
    router = _EastmoneyRouter({"": [_em_ann_page([])]})
    mocker.patch.object(ann, "urlopen", side_effect=router)
    df = _em_source().fetch({})
    assert df.empty and list(df.columns) == ANN_COLUMNS
