import datetime as dt
import os

import psycopg
import pytest

from collector.store.writer import Store

DB = os.environ.get("DATABASE_URL")


@pytest.mark.skipif(not DB, reason="需要 DATABASE_URL")
def test_upsert_stock_valuation_daily_idempotent():
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
    store = Store()
    with psycopg.connect(DB) as conn:
        store.upsert(conn, "stock_valuation_daily", [row])
        store.upsert(conn, "stock_valuation_daily", [row])  # 幂等
        with conn.cursor() as cur:
            cur.execute(
                "SELECT count(*) FROM stock_valuation_daily WHERE trading_day=%s AND stock_code=%s", (day, "600519")
            )
            assert cur.fetchone()[0] == 1


@pytest.mark.skipif(not DB, reason="需要 DATABASE_URL")
def test_upsert_stock_financial_idempotent():
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
    store = Store()
    with psycopg.connect(DB) as conn:
        store.upsert(conn, "stock_financial", [row])
        store.upsert(conn, "stock_financial", [row])
        with conn.cursor() as cur:
            cur.execute(
                "SELECT count(*) FROM stock_financial WHERE report_date=%s AND stock_code=%s",
                (dt.date(2026, 6, 30), "600519"),
            )
            assert cur.fetchone()[0] == 1
