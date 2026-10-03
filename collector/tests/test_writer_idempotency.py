"""T3 落库铁律：十一张业务目标表的真实 PG 幂等 upsert（valuation_snapshot 见 test_writer.py）。

每张表：同主键重复 upsert → 不产生重复行、行数仍为 1、非键字段被更新为新值。
upsert 键与各表 DDL（后端 Flyway V3/V4/V7/V13/V18、intelligence V3）对齐，见 collector/store/writer.py。
"""

import datetime as dt

from collector.store.writer import Store

DAY = dt.date(2026, 8, 28)


def test_treasury_yield_curve_upsert_idempotent(pg_conn):
    """冲突键 (trading_day, term)，更新 yield。"""
    store = Store()
    rec = {"trading_day": DAY, "term": "10Y", "yield": 2.1}
    store.upsert(pg_conn, "treasury_yield_curve", [rec])
    store.upsert(pg_conn, "treasury_yield_curve", [{**rec, "yield": 2.3}])
    rows = pg_conn.execute(
        "SELECT yield FROM treasury_yield_curve WHERE trading_day=%s AND term=%s", (DAY, "10Y")
    ).fetchall()
    assert len(rows) == 1
    assert float(rows[0][0]) == 2.3


def test_index_valuation_history_upsert_idempotent(pg_conn):
    """冲突键 (trading_day, index_code)，更新 pe/pb/dividend_yield。"""
    store = Store()
    rec = {
        "trading_day": DAY,
        "index_code": "000300",
        "index_name": "沪深300",
        "pe": 12.0,
        "pb": 1.3,
        "dividend_yield": 2.5,
    }
    store.upsert(pg_conn, "index_valuation_history", [rec])
    store.upsert(pg_conn, "index_valuation_history", [{**rec, "pe": 13.5, "dividend_yield": 2.8}])
    rows = pg_conn.execute(
        "SELECT pe, pb, dividend_yield FROM index_valuation_history WHERE trading_day=%s AND index_code=%s",
        (DAY, "000300"),
    ).fetchall()
    assert len(rows) == 1
    pe, pb, dividend_yield = (float(v) for v in rows[0])
    assert (pe, pb, dividend_yield) == (13.5, 1.3, 2.8)


def test_industry_valuation_upsert_idempotent(pg_conn):
    """冲突键 (trading_day, industry_code)，更新 pe/pb。"""
    store = Store()
    rec = {"trading_day": DAY, "industry_code": "801080", "industry_name": "电子", "pe": 30.0, "pb": 3.0}
    store.upsert(pg_conn, "industry_valuation", [rec])
    store.upsert(pg_conn, "industry_valuation", [{**rec, "pe": 31.5}])
    rows = pg_conn.execute(
        "SELECT pe, pb FROM industry_valuation WHERE trading_day=%s AND industry_code=%s", (DAY, "801080")
    ).fetchall()
    assert len(rows) == 1
    assert (float(rows[0][0]), float(rows[0][1])) == (31.5, 3.0)


def test_shenwan_industry_mapping_upsert_idempotent(pg_conn):
    """冲突键 (stock_code)，更新 industry_code/industry_name。"""
    store = Store()
    rec = {
        "stock_code": "600519",
        "stock_name": "贵州茅台",
        "industry_code": "801120",
        "industry_name": "食品饮料",
    }
    store.upsert(pg_conn, "shenwan_industry_mapping", [rec])
    store.upsert(
        pg_conn,
        "shenwan_industry_mapping",
        [{**rec, "industry_code": "801121", "industry_name": "白酒"}],
    )
    rows = pg_conn.execute(
        "SELECT industry_code, industry_name FROM shenwan_industry_mapping WHERE stock_code=%s", ("600519",)
    ).fetchall()
    assert rows == [("801121", "白酒")]


def test_index_constituent_upsert_idempotent(pg_conn):
    """冲突键 (index_code, stock_code)，更新 stock_name/weight。"""
    store = Store()
    rec = {"index_code": "000300", "stock_code": "600519", "stock_name": "贵州茅台", "weight": 5.0}
    store.upsert(pg_conn, "index_constituent", [rec])
    store.upsert(pg_conn, "index_constituent", [{**rec, "weight": 6.5}])
    rows = pg_conn.execute(
        "SELECT stock_name, weight FROM index_constituent WHERE index_code=%s AND stock_code=%s",
        ("000300", "600519"),
    ).fetchall()
    assert len(rows) == 1
    assert rows[0][0] == "贵州茅台"
    assert float(rows[0][1]) == 6.5


def test_index_close_history_upsert_idempotent(pg_conn):
    """冲突键 (trading_day, index_code)，更新 close（MS-07 基准指数收盘价，Flyway V13）。"""
    store = Store()
    rec = {"trading_day": DAY, "index_code": "000300", "index_name": "沪深300", "close": 3900.12}
    store.upsert(pg_conn, "index_close_history", [rec])
    store.upsert(pg_conn, "index_close_history", [{**rec, "close": 3910.5}])
    rows = pg_conn.execute(
        "SELECT close FROM index_close_history WHERE trading_day=%s AND index_code=%s", (DAY, "000300")
    ).fetchall()
    assert len(rows) == 1
    assert float(rows[0][0]) == 3910.5


def test_etf_basic_upsert_idempotent(pg_conn):
    """冲突键 (fund_code)（MS-14 P3 Task 13，Flyway V18）：周更 upsert 更新目录字段、
    刷新 updated_at，且不清 tracking_error_1y（该列由 Task 15 误差任务单独回写）。"""
    store = Store()
    rec = {
        "fund_code": "510300",
        "fund_name": "沪深300ETF华泰柏瑞",
        "fee_rate": 0.2,
        "scale": 948.7,
        "tracking_index_code": "000300",
        "tracking_index_name": "沪深300指数",
        "category": "宽基",
    }
    store.upsert(pg_conn, "etf_basic", [rec])
    # 模拟 Task 15 已写入跟踪误差
    pg_conn.execute("UPDATE etf_basic SET tracking_error_1y=0.012345 WHERE fund_code='510300'")
    store.upsert(pg_conn, "etf_basic", [{**rec, "fee_rate": 0.15}])
    rows = pg_conn.execute("SELECT fee_rate, tracking_error_1y FROM etf_basic WHERE fund_code='510300'").fetchall()
    assert len(rows) == 1
    assert float(rows[0][0]) == 0.15
    assert float(rows[0][1]) == 0.012345  # 周更不触碰误差列


def test_intelligence_news_raw_upsert_idempotent(pg_conn):
    """冲突键 (source, external_id)（MS-20 P1，Flyway V3）：*/10 高频重跑幂等——
    title/summary/url/stock_tags 刷新、fetched_at=now()，published_at 保首见不更新
    （发布时刻是源站事实，非采集观测）；业务键含 source，两源同 external_id 互不覆盖。"""
    store = Store()
    utc8 = dt.timezone(dt.timedelta(hours=8))
    rec = {
        "source": "eastmoney_724",
        "external_id": "202609291029161",
        "title": "央行开展1000亿元逆回购",
        "summary": "央行公告内容",
        "published_at": dt.datetime(2026, 9, 29, 10, 29, 16, tzinfo=utc8),
        "url": "https://finance.eastmoney.com/a/202609291029161.html",
        "stock_tags": '["1.600825", "90.BK1365"]',
    }
    store.upsert(pg_conn, "intelligence_news_raw", [rec])
    # 首见后手动回拨 fetched_at，验证第二次 upsert 确实刷新观测时刻
    pg_conn.execute("UPDATE intelligence_news_raw SET fetched_at = now() - interval '1 hour'")
    store.upsert(
        pg_conn,
        "intelligence_news_raw",
        [
            {
                **rec,
                "title": "（更新）央行开展1000亿元逆回购",
                "summary": None,
                "published_at": dt.datetime(2026, 9, 29, 11, 0, 0, tzinfo=utc8),  # 更晚，不应覆盖
                "url": None,
                "stock_tags": "[]",
            }
        ],
    )
    rows = pg_conn.execute(
        "SELECT title, summary, published_at, url, stock_tags, fetched_at >= now() - interval '1 minute'"
        " FROM intelligence_news_raw WHERE source=%s AND external_id=%s",
        ("eastmoney_724", "202609291029161"),
    ).fetchall()
    assert len(rows) == 1
    title, summary, published_at, url, stock_tags, fetched_recent = rows[0]
    assert title == "（更新）央行开展1000亿元逆回购"
    assert summary is None
    assert url is None
    assert published_at == dt.datetime(2026, 9, 29, 10, 29, 16, tzinfo=utc8)  # 保首见
    assert stock_tags == []  # JSON 字符串 → JSONB 写入，psycopg 读回已解析 list
    assert fetched_recent is True  # fetched_at=now() 已刷新

    # 业务键含 source：新浪同 external_id 是另一条记录，不互相覆盖
    store.upsert(
        pg_conn,
        "intelligence_news_raw",
        [{**rec, "source": "sina_zhibo", "title": "新浪侧同名快讯"}],
    )
    count = pg_conn.execute(
        "SELECT count(*) FROM intelligence_news_raw WHERE external_id=%s", ("202609291029161",)
    ).fetchone()[0]
    assert count == 2


def test_intelligence_announcement_upsert_idempotent(pg_conn):
    """冲突键 (source, external_id)（MS-21 P2，Flyway V3）：双任务重跑幂等——
    title/stock_name/ann_type_source/major/pdf_url 刷新、fetched_at=now()，
    published_at 保首见不更新（披露时刻是源站事实，非采集观测）；业务键含 source，
    巨潮/东财同 external_id 互不覆盖。"""
    store = Store()
    utc8 = dt.timezone(dt.timedelta(hours=8))
    rec = {
        "source": "cninfo",
        "external_id": "122236472",
        "stock_code": "600519",
        "stock_name": "贵州茅台",
        "title": "贵州茅台2025年半年度报告",
        "ann_type_source": "category_bndbg_szsh",
        "major": True,
        "published_at": dt.datetime(2026, 9, 28, 0, 0, 0, tzinfo=utc8),
        "pdf_url": "https://static.cninfo.com.cn/finalpage/2026-09-28/122236472.PDF",
    }
    store.upsert(pg_conn, "intelligence_announcement", [rec])
    # 首见后手动回拨 fetched_at，验证第二次 upsert 确实刷新观测时刻
    pg_conn.execute("UPDATE intelligence_announcement SET fetched_at = now() - interval '1 hour'")
    store.upsert(
        pg_conn,
        "intelligence_announcement",
        [
            {
                **rec,
                "stock_name": "贵州茅台股份有限公司",
                "title": "（更新）贵州茅台2025年半年度报告（全文）",
                "ann_type_source": None,
                "major": False,  # 栏目预判翻转（备源口径）也应刷新
                "published_at": dt.datetime(2026, 9, 28, 21, 0, 11, tzinfo=utc8),  # 更晚，不应覆盖
                "pdf_url": None,
            }
        ],
    )
    rows = pg_conn.execute(
        "SELECT stock_name, title, ann_type_source, major, published_at, pdf_url,"
        " fetched_at >= now() - interval '1 minute'"
        " FROM intelligence_announcement WHERE source=%s AND external_id=%s",
        ("cninfo", "122236472"),
    ).fetchall()
    assert len(rows) == 1
    stock_name, title, ann_type_source, major, published_at, pdf_url, fetched_recent = rows[0]
    assert stock_name == "贵州茅台股份有限公司"  # 非键列刷新
    assert title == "（更新）贵州茅台2025年半年度报告（全文）"
    assert ann_type_source is None
    assert major is False
    assert pdf_url is None
    assert published_at == dt.datetime(2026, 9, 28, 0, 0, 0, tzinfo=utc8)  # 保首见
    assert fetched_recent is True  # fetched_at=now() 已刷新

    # 业务键含 source：东财同 external_id 是另一条记录，不互相覆盖
    store.upsert(
        pg_conn,
        "intelligence_announcement",
        [{**rec, "source": "eastmoney_ann", "title": "东财侧同号公告"}],
    )
    count = pg_conn.execute(
        "SELECT count(*) FROM intelligence_announcement WHERE external_id=%s", ("122236472",)
    ).fetchone()[0]
    assert count == 2


def test_intelligence_macro_series_upsert_idempotent(pg_conn):
    """冲突键 (indicator, period)（MS-22 P3，Flyway V3）：发布窗口幂等多跑——
    value/yoy/source_url/source_note 刷新；V3 实况本表**无 fetched_at 列**（无观测
    时刻可刷）；键含 indicator：同月不同指标互不覆盖（社融双源 AFMI/M2 各自成行）。"""
    store = Store()
    rec = {
        "indicator": "CPI",
        "period": "2026-08",
        "period_type": "MONTH",
        "value": 0.8,
        "yoy": None,
        "source_url": "https://www.stats.gov.cn/sj/zxfb/202609/t20260913_1959519.html",
        "source_note": "环比涨跌幅（%）0.4",
    }
    store.upsert(pg_conn, "intelligence_macro_series", [rec])
    # 发布窗口次日重跑：数值修正（0.8 → 0.9）应刷新
    store.upsert(
        pg_conn,
        "intelligence_macro_series",
        [{**rec, "value": 0.9, "source_note": "环比涨跌幅（%）0.5"}],
    )
    rows = pg_conn.execute(
        "SELECT value, source_note FROM intelligence_macro_series WHERE indicator=%s AND period=%s",
        ("CPI", "2026-08"),
    ).fetchall()
    assert len(rows) == 1
    assert float(rows[0][0]) == 0.9
    assert rows[0][1] == "环比涨跌幅（%）0.5"

    # 键含 indicator：LPR 日频与 AFMI/M2 月度是独立行，互不覆盖
    store.upsert(
        pg_conn,
        "intelligence_macro_series",
        [
            {**rec, "indicator": "LPR", "period": "2026-09-20", "period_type": "DAY", "value": 3.0},
            {**rec, "indicator": "AFMI", "value": 16577.0},
        ],
    )
    count = pg_conn.execute("SELECT count(*) FROM intelligence_macro_series").fetchone()[0]
    assert count == 3  # CPI 2026-08 / LPR 2026-09-20 / AFMI 2026-08


def test_intelligence_policy_raw_upsert_idempotent(pg_conn):
    """冲突键 (source, external_id)（MS-22 P3，Flyway V3）：每日重跑幂等——
    title/url/content_text 刷新；published_at 保首见不更新（发布时刻是源站事实，
    非采集观测）；V3 实况本表**无 fetched_at 列**；业务键含 source，两部委同
    external_id 互不覆盖。"""
    store = Store()
    utc8 = dt.timezone(dt.timedelta(hours=8))
    rec = {
        "source": "pboc",
        "external_id": "202609301946",
        "title": "中国人民银行 国家金融监督管理总局关于印发《系统重要性银行附加监管规定（试行）》的通知",
        "url": "https://www.pbc.gov.cn/goutongjiaoliu/113456/113469/202609301946/index.html",
        "published_at": dt.datetime(2026, 9, 30, 19, 46, 0, tzinfo=utc8),
        "content_text": "中国人民银行 国家金融监督管理总局……",
    }
    updated_title = "（更新）中国人民银行 国家金融监督管理总局关于印发《系统重要性银行附加监管规定（试行）》的通知"
    store.upsert(pg_conn, "intelligence_policy_raw", [rec])
    store.upsert(
        pg_conn,
        "intelligence_policy_raw",
        [
            {
                **rec,
                "title": updated_title,
                "url": None,
                "content_text": None,
                "published_at": dt.datetime(2026, 9, 30, 20, 0, 0, tzinfo=utc8),  # 更晚，不应覆盖
            }
        ],
    )
    rows = pg_conn.execute(
        "SELECT title, url, content_text, published_at FROM intelligence_policy_raw WHERE source=%s AND external_id=%s",
        ("pboc", "202609301946"),
    ).fetchall()
    assert len(rows) == 1
    title, url, content_text, published_at = rows[0]
    assert title.startswith("（更新）")  # 非键列刷新
    assert url is None
    assert content_text is None
    assert published_at == dt.datetime(2026, 9, 30, 19, 46, 0, tzinfo=utc8)  # 保首见

    # 业务键含 source：csrc 同 external_id 是另一条记录，不互相覆盖
    store.upsert(
        pg_conn,
        "intelligence_policy_raw",
        [{**rec, "source": "csrc", "title": "证监会侧同号文"}],
    )
    count = pg_conn.execute(
        "SELECT count(*) FROM intelligence_policy_raw WHERE external_id=%s", ("202609301946",)
    ).fetchone()[0]
    assert count == 2


def test_index_constituent_replaces_members_on_rerun(pg_conn):
    """C-9：半年任务重跑是快照语义——先删后插，被调出指数的成员不再残留旧行，
    且不影响本次未涉及的其它指数。"""
    store = Store()
    store.upsert(
        pg_conn,
        "index_constituent",
        [
            {"index_code": "000300", "stock_code": "600519", "stock_name": "贵州茅台", "weight": 5.0},
            {"index_code": "000300", "stock_code": "000001", "stock_name": "平安银行", "weight": 3.0},
            {"index_code": "000905", "stock_code": "600519", "stock_name": "贵州茅台", "weight": 2.0},
        ],
    )
    # 第二次快照：000001 被调出，新增 000858
    store.upsert(
        pg_conn,
        "index_constituent",
        [
            {"index_code": "000300", "stock_code": "600519", "stock_name": "贵州茅台", "weight": 6.0},
            {"index_code": "000300", "stock_code": "000858", "stock_name": "五粮液", "weight": 4.0},
        ],
    )
    hs300 = pg_conn.execute("SELECT stock_code FROM index_constituent WHERE index_code='000300'").fetchall()
    assert sorted(r[0] for r in hs300) == ["000858", "600519"]  # 000001 已剔除，000858 新纳入
    # 未涉及的其它指数不受影响
    zz500 = pg_conn.execute("SELECT stock_code FROM index_constituent WHERE index_code='000905'").fetchall()
    assert [r[0] for r in zz500] == ["600519"]
