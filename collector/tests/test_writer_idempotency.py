"""T3 落库铁律：六张业务目标表的真实 PG 幂等 upsert（valuation_snapshot 见 test_writer.py）。

每张表：同主键重复 upsert → 不产生重复行、行数仍为 1、非键字段被更新为新值。
upsert 键与各表 DDL（后端 Flyway V3/V4）对齐，见 collector/store/writer.py。
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
