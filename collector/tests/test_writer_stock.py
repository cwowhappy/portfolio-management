import datetime as dt

from collector.store.writer import Store


def test_upsert_stock_valuation_daily_idempotent(pg_conn):
    """冲突键 (trading_day, stock_code)，同主键重复 upsert 后仍为一行。"""
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
    }
    store.upsert(pg_conn, "stock_valuation_daily", [row])
    store.upsert(pg_conn, "stock_valuation_daily", [row])  # 幂等
    count = pg_conn.execute(
        "SELECT count(*) FROM stock_valuation_daily WHERE trading_day=%s AND stock_code=%s", (day, "600519")
    ).fetchone()[0]
    assert count == 1


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
