"""MS-20 P1 Task 6 新闻两源（东财 7×24 主源 + 新浪 zhibo 降级源）。

样本形态取探测报告（09-调研报告/2026-09-29-MS20-数据源探测报告.md）§1/§2 实测原文：
- 东财：data.fastNewsList[]（code=条目唯一 id，顶层 code="1" 为接口状态，勿混淆）、
  sortEnd 游标（恒等于本页末条 realSort，向更旧翻页）、stockList 为前缀字符串数组。
- 新浪：result.data.feed.list[]（id 自增、rich_text 无独立 title 需剥【】、
  ext 为 JSON 字符串、ext.stocks 仅取 market=cn）。
契约：输出列固定 NEWS_COLUMNS；stock_tags 为 JSON 字符串；增量 = 分页拉取至首个已存
external_id 截断（conn_factory 查最近 200 条）；请求级失败重试 3 次后 SourceError。
"""

import datetime as dt
import json
import urllib.error
from unittest.mock import MagicMock

import pytest

import collector.sources.news as news
from collector.sources.base import SourceError
from collector.sources.constants import NEWS_PAGE_INTERVAL
from collector.sources.news import NEWS_COLUMNS

_EXPECTED_TS = dt.datetime(2026, 9, 29, 8, 53, 9, tzinfo=dt.timezone(dt.timedelta(hours=8)))


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

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def execute(self, sql, *args, **kwargs):
        self.sql = sql

    def fetchall(self):
        return self._rows


class _ExistingConn:
    """已存集合 conn_factory 假对象：fetchall → [(external_id,), ...]。"""

    def __init__(self, rows):
        self._rows = rows

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def cursor(self):
        return _ExistingCursor(self._rows)


def _existing_factory(ids):
    rows = [(i,) for i in ids]
    return lambda: _ExistingConn(rows)


def _em_item(code, title="标题", summary=None, show_time="2026-09-29 08:53:09", stock_list=..., **extra):
    item = {
        "code": code,
        "title": title,
        "summary": summary if summary is not None else f"【{title}】正文",
        "showTime": show_time,
        "realSort": "1790643189065083",
    }
    if stock_list is not ...:
        item["stockList"] = stock_list
    item.update(extra)
    return item


def _em_page(items, sort_end="1790642838057217"):
    return {"req_trace": "1", "code": "1", "message": "success", "data": {"sortEnd": sort_end, "fastNewsList": items}}


def _sina_item(
    item_id,
    rich_text="【标题甲】正文内容",
    create_time="2026-09-29 08:55:17",
    ext=...,
    docurl="https://finance.sina.cn/7x24/detail.html",
    **extra,
):
    item = {"id": item_id, "rich_text": rich_text, "create_time": create_time}
    if ext is not ...:
        item["ext"] = ext if isinstance(ext, str) else json.dumps(ext, ensure_ascii=False)
    if docurl is not None:
        item["docurl"] = docurl
    item.update(extra)
    return item


def _sina_page(items, code=0):
    return {
        "result": {
            "status": {"code": code, "msg": "OK"},
            "data": {"feed": {"list": items, "max_id": 5117491, "min_id": 5117469}},
        }
    }


def _no_sleep(_seconds):
    pass


# ---------------------------------------------------------------- 东财主源


def test_eastmoney_parses_columns_and_truncates_at_existing_id(mocker):
    """首页解析 + 输出列契约 + 首个已存 external_id 截断（D1 增量策略）。"""
    mocker.patch.object(
        news, "urlopen", return_value=_resp(_em_page([_em_item("n2", stock_list=["1.600519"]), _em_item("n1")]))
    )
    src = news.EastmoneyFastNewsSource(
        "eastmoney_fast_news", conn_factory=_existing_factory(["n1"]), sleep_fn=_no_sleep
    )
    df = src.fetch({})
    assert list(df.columns) == NEWS_COLUMNS
    assert list(df["external_id"]) == ["n2"]  # n1 已存 → 截断（含其后的更旧条目一并不拉）
    row = df.iloc[0]
    assert row["source"] == "eastmoney_724"  # DB source 列值（探测报告 §1.3；VARCHAR(16)）
    assert row["title"] == "标题"
    assert row["summary"] == "【标题】正文"
    assert row["published_at"] == _EXPECTED_TS
    assert row["url"] == "https://finance.eastmoney.com/a/n2.html"  # §1.3 详情模板
    assert row["stock_tags"] == '["1.600519"]'


def test_eastmoney_stock_list_raw_passthrough(mocker):
    """stockList 原样入库（报告 §1.3/§1.5）：150. 基金、0. 前缀混深市基金不在采集侧过滤，
    A 股/板块解析规则（60/68/00/30/92 六位 + 90.BK）留给抽取侧；缺失 → '[]'。"""
    items = [
        _em_item("a", stock_list=["1.600825", "0.920982", "90.BK0437", "150.161027", "0.161032"]),
        _em_item("b"),  # 无 stockList
    ]
    mocker.patch.object(news, "urlopen", return_value=_resp(_em_page(items, sort_end="")))
    src = news.EastmoneyFastNewsSource("eastmoney_fast_news", sleep_fn=_no_sleep)
    df = src.fetch({})
    assert df.iloc[0]["stock_tags"] == '["1.600825", "0.920982", "90.BK0437", "150.161027", "0.161032"]'
    assert df.iloc[1]["stock_tags"] == "[]"


def test_eastmoney_cursor_pagination_with_politeness_interval(mocker):
    """sortEnd 游标翻页（首页空、其后传上页 data.sortEnd）+ 翻页礼貌间隔 + 跨页截断。"""
    page1 = _em_page([_em_item("a"), _em_item("b")], sort_end="cur1")
    page2 = _em_page([_em_item("c")], sort_end="cur2")  # c 已存 → 第二页内截断
    m = mocker.patch.object(news, "urlopen", side_effect=[_resp(page1), _resp(page2)])
    sleeps = []
    src = news.EastmoneyFastNewsSource(
        "eastmoney_fast_news", conn_factory=_existing_factory(["c"]), sleep_fn=sleeps.append
    )
    df = src.fetch({})
    assert list(df["external_id"]) == ["a", "b"]
    assert "sortEnd=" in m.call_args_list[0].args[0].full_url  # 首页游标传空
    assert "sortEnd=cur1" in m.call_args_list[1].args[0].full_url  # 第二页传上页 sortEnd
    assert sleeps == [NEWS_PAGE_INTERVAL]  # 页间间隔（重试间隔另算）


def test_eastmoney_full_paging_without_conn_factory(mocker):
    """conn_factory=None（测试/无 DB）：不做已存截断，翻至空页为止。"""
    page1 = _em_page([_em_item("a"), _em_item("b")], sort_end="cur1")
    page2 = _em_page([])
    m = mocker.patch.object(news, "urlopen", side_effect=[_resp(page1), _resp(page2)])
    src = news.EastmoneyFastNewsSource("eastmoney_fast_news", sleep_fn=_no_sleep)
    df = src.fetch({})
    assert list(df["external_id"]) == ["a", "b"]
    assert m.call_count == 2  # 空页停，不再翻


def test_eastmoney_explicit_range_skips_truncation(mocker):
    """显式 params start/end（backfill 意图）优先于已存集合截断（D1）。"""
    mocker.patch.object(news, "urlopen", return_value=_resp(_em_page([_em_item("n2"), _em_item("n1")], sort_end="")))
    src = news.EastmoneyFastNewsSource(
        "eastmoney_fast_news", conn_factory=_existing_factory(["n1"]), sleep_fn=_no_sleep
    )
    df = src.fetch({"start": "2026-09-28", "end": "2026-09-29"})
    assert list(df["external_id"]) == ["n2", "n1"]  # 已存 n1 不截断


def test_eastmoney_drops_item_with_bad_showtime_and_warns(mocker):
    """单条解析失败丢弃 + last_warnings 记录（不改终态）；好条目不受影响。"""
    items = [_em_item("bad", show_time="不是时间"), _em_item("good")]
    mocker.patch.object(news, "urlopen", return_value=_resp(_em_page(items, sort_end="")))
    src = news.EastmoneyFastNewsSource("eastmoney_fast_news", sleep_fn=_no_sleep)
    df = src.fetch({})
    assert list(df["external_id"]) == ["good"]
    assert src.last_warnings and "bad" in src.last_warnings[0]


def test_eastmoney_http_error_retries_three_then_source_error(mocker):
    """请求级异常重试 NEWS_RETRY_ATTEMPTS 次（页间礼貌间隔复用）后抛 SourceError 走降级。"""
    m = mocker.patch.object(news, "urlopen", side_effect=urllib.error.URLError("boom"))
    sleeps = []
    src = news.EastmoneyFastNewsSource("eastmoney_fast_news", sleep_fn=sleeps.append)
    with pytest.raises(SourceError, match="连续 3 次"):
        src.fetch({})
    assert m.call_count == 3
    assert sleeps == [NEWS_PAGE_INTERVAL, NEWS_PAGE_INTERVAL]  # 重试间 2 次间隔


def test_eastmoney_non_200_status_source_error(mocker):
    """HTTP 非 200 → SourceError（selector 换源），不自吞。"""
    mocker.patch.object(news, "urlopen", return_value=_resp({}, status=502))
    src = news.EastmoneyFastNewsSource("eastmoney_fast_news", attempts=1, sleep_fn=_no_sleep)
    with pytest.raises(SourceError, match="502"):
        src.fetch({})


def test_eastmoney_garbage_body_source_error(mocker):
    """响应体非 JSON → SourceError。"""
    mocker.patch.object(news, "urlopen", return_value=_resp(b"<html>blocked</html>"))
    src = news.EastmoneyFastNewsSource("eastmoney_fast_news", attempts=1, sleep_fn=_no_sleep)
    with pytest.raises(SourceError):
        src.fetch({})


def test_eastmoney_structural_drift_source_error(mocker):
    """响应 200 但 data.fastNewsList 缺失（上游结构漂移）→ 直接 SourceError，不重试。"""
    m = mocker.patch.object(news, "urlopen", return_value=_resp({"code": "1", "data": {}}))
    src = news.EastmoneyFastNewsSource("eastmoney_fast_news", sleep_fn=_no_sleep)
    with pytest.raises(SourceError, match="结构漂移"):
        src.fetch({})
    assert m.call_count == 1


# ---------------------------------------------------------------- 新浪降级源


def test_sina_parses_filters_cn_stocks_and_truncates(mocker):
    """rich_text 剥【】做 title、ext 内 stocks 仅取 market=cn（fund/us 丢弃）、首存截断。"""
    ext = {
        "stocks": [
            {"market": "cn", "symbol": "sz002369", "key": "测试股"},
            {"market": "fund", "symbol": "of510300", "key": "沪深300ETF"},
            {"market": "us", "symbol": "aapl", "key": "苹果"},
        ],
        "needCMSLink": True,
        "docurl": "https://finance.sina.com.cn/7x24/doc-initmzhm9338013.shtml",
    }
    items = [_sina_item(5117491, ext=ext), _sina_item(5117469)]
    m = mocker.patch.object(news, "urlopen", return_value=_resp(_sina_page(items)))
    src = news.SinaZhiboNewsSource("sina_zhibo_news", conn_factory=_existing_factory(["5117469"]), sleep_fn=_no_sleep)
    df = src.fetch({})
    assert list(df.columns) == NEWS_COLUMNS
    assert list(df["external_id"]) == ["5117491"]
    row = df.iloc[0]
    assert row["source"] == "sina_zhibo"
    assert row["title"] == "标题甲"  # 【】内段
    assert row["summary"] == "【标题甲】正文内容"  # 全文含前缀
    assert row["published_at"] == dt.datetime(2026, 9, 29, 8, 55, 17, tzinfo=dt.timezone(dt.timedelta(hours=8)))
    assert row["url"] == "https://finance.sina.cn/7x24/detail.html"  # 自带 docurl
    assert row["stock_tags"] == '[{"code": "002369", "name": "测试股"}]'  # symbol 去市场前缀、key=名
    req = m.call_args_list[0].args[0]
    assert "page=1" in req.full_url and "zhibo_id=152" in req.full_url
    assert req.headers["Referer"] == "https://finance.sina.com.cn/7x24/"  # §2.1 带 Referer 更稳


def test_sina_title_fallback_without_bracket(mocker):
    """rich_text 无【】前缀 → title 退化为全文（title NOT NULL 不可空）。"""
    mocker.patch.object(
        news,
        "urlopen",
        return_value=_resp(
            _sina_page(
                [_sina_item(1, rich_text="没有括号的快讯")],
            )
        ),
    )
    src = news.SinaZhiboNewsSource("sina_zhibo_news", sleep_fn=_no_sleep)
    df = src.fetch({})
    assert df.iloc[0]["title"] == "没有括号的快讯"


def test_sina_ext_parse_failure_empty_tags_with_warning(mocker):
    """ext 解析失败 → 标的标签置空不阻塞（§2.5）+ last_warnings；条目本身保留。"""
    items = [_sina_item(1, ext="{不是json"), _sina_item(2, ext=...)]  # 第二条无 ext
    mocker.patch.object(news, "urlopen", return_value=_resp(_sina_page(items)))
    src = news.SinaZhiboNewsSource("sina_zhibo_news", sleep_fn=_no_sleep)
    df = src.fetch({})
    assert len(df) == 2
    assert df.iloc[0]["stock_tags"] == "[]" and df.iloc[1]["stock_tags"] == "[]"
    assert src.last_warnings and "ext" in src.last_warnings[0]


def test_sina_url_falls_back_to_ext_docurl(mocker):
    """条目无 docurl 时用 ext 内 www 版 docurl；两者皆无 → None（url 可空）。"""
    ext = {"stocks": [], "docurl": "https://finance.sina.com.cn/7x24/doc-x.shtml"}
    items = [_sina_item(1, ext=ext, docurl=None), _sina_item(2, ext=..., docurl=None)]
    mocker.patch.object(news, "urlopen", return_value=_resp(_sina_page(items)))
    src = news.SinaZhiboNewsSource("sina_zhibo_news", sleep_fn=_no_sleep)
    df = src.fetch({})
    assert df.iloc[0]["url"] == "https://finance.sina.com.cn/7x24/doc-x.shtml"
    assert df.iloc[1]["url"] is None


def test_sina_page_number_pagination(mocker):
    """page=N 页码翻页（p1→p2 边界衔接零重叠，§2.4），页内已存 id 截断。"""
    m = mocker.patch.object(
        news,
        "urlopen",
        side_effect=[_resp(_sina_page([_sina_item(3)])), _resp(_sina_page([_sina_item(2), _sina_item(1)]))],
    )
    src = news.SinaZhiboNewsSource("sina_zhibo_news", conn_factory=_existing_factory(["2"]), sleep_fn=_no_sleep)
    df = src.fetch({})
    assert list(df["external_id"]) == ["3"]
    assert "page=1" in m.call_args_list[0].args[0].full_url
    assert "page=2" in m.call_args_list[1].args[0].full_url


def test_sina_status_code_nonzero_source_error(mocker):
    """result.status.code != 0 → SourceError（接口不可用）。"""
    mocker.patch.object(news, "urlopen", return_value=_resp(_sina_page([], code=1)))
    src = news.SinaZhiboNewsSource("sina_zhibo_news", attempts=1, sleep_fn=_no_sleep)
    with pytest.raises(SourceError, match="status.code"):
        src.fetch({})


def test_sina_structural_drift_source_error(mocker):
    """result.data.feed.list 缺失（结构漂移）→ SourceError 不自吞。"""
    mocker.patch.object(news, "urlopen", return_value=_resp({"result": {"status": {"code": 0}, "data": {}}}))
    src = news.SinaZhiboNewsSource("sina_zhibo_news", sleep_fn=_no_sleep)
    with pytest.raises(SourceError, match="结构漂移"):
        src.fetch({})


def test_empty_feed_returns_empty_frame_with_columns(mocker):
    """空 feed → 0 行但列契约保持（executor/writer 0 行路径）。"""
    mocker.patch.object(news, "urlopen", return_value=_resp(_em_page([], sort_end="")))
    src = news.EastmoneyFastNewsSource("eastmoney_fast_news", sleep_fn=_no_sleep)
    df = src.fetch({})
    assert df.empty and list(df.columns) == NEWS_COLUMNS
