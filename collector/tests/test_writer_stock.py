import datetime as dt

import pytest

from collector.store.writer import Store


def test_upsert_stock_valuation_daily_idempotent(pg_conn):
    """冲突键 (trading_day, stock_code)，同主键重复 upsert 后仍为一行；close 列（V13）随写并被更新。"""
    store = Store()
    day = dt.date(2026, 8, 27)
    row = {
        "trading_day": day,
        "stock_code": "600519",
        "stock_name": "贵州茅台",
        "pe_ttm": 22.5,
        "pb": 7.8,
        "dividend_yield": 2.1,
        "total_mv": 2100000000000.0,
        "circ_mv": 2100000000000.0,
        "turnover_rate": 0.35,
        "close": 1500.0,
    }
    store.upsert(pg_conn, "stock_valuation_daily", [row])
    store.upsert(pg_conn, "stock_valuation_daily", [row])  # 幂等
    count = pg_conn.execute(
        "SELECT count(*) FROM stock_valuation_daily WHERE trading_day=%s AND stock_code=%s", (day, "600519")
    ).fetchone()[0]
    assert count == 1
    close = pg_conn.execute(
        "SELECT close FROM stock_valuation_daily WHERE trading_day=%s AND stock_code=%s", (day, "600519")
    ).fetchone()[0]
    assert float(close) == 1500.0  # close 列真实落库（非静默丢弃）


def test_upsert_stock_financial_idempotent(pg_conn):
    """冲突键 (report_date, stock_code)，同主键重复 upsert 后仍为一行。"""
    store = Store()
    row = {
        "report_date": dt.date(2026, 6, 30),
        "stock_code": "600519",
        "roe": 24.5,
        "roa": 18.2,
        "gross_margin": 91.2,
        "debt_to_assets": 21.3,
        "current_ratio": 3.8,
        "revenue_yoy": 16.8,
        "netprofit_yoy": 15.2,
    }
    store.upsert(pg_conn, "stock_financial", [row])
    store.upsert(pg_conn, "stock_financial", [row])
    count = pg_conn.execute(
        "SELECT count(*) FROM stock_financial WHERE report_date=%s AND stock_code=%s",
        (dt.date(2026, 6, 30), "600519"),
    ).fetchone()[0]
    assert count == 1


def test_upsert_stock_financial_with_revenue(pg_conn):
    """V15 后 revenue 列可写入并可回读（MS-09）。"""
    store = Store()
    record = {
        "report_date": "20260630", "stock_code": "600519", "roe": 30.0, "roa": 20.0,
        "gross_margin": 91.0, "debt_to_assets": 20.0, "current_ratio": 4.0,
        "revenue_yoy": 15.0, "netprofit_yoy": 15.0, "revenue": 1_234_567_890.0,
    }
    written = store.upsert(pg_conn, "stock_financial", [record])
    assert written == 1
    with pg_conn.cursor() as cur:
        cur.execute("SELECT revenue FROM stock_financial WHERE stock_code = %s", ("600519",))
        assert cur.fetchone()[0] == pytest.approx(1_234_567_890.0)
