import datetime as dt
from typing import Iterable

import psycopg

SNAPSHOT_UPSERT = """
INSERT INTO valuation_snapshot (trading_day, pe_median, pb_median, net_breaker_count, net_breaker_ratio)
VALUES (%s, %s, %s, %s, %s)
ON CONFLICT (trading_day) DO UPDATE SET
  pe_median = EXCLUDED.pe_median,
  pb_median = EXCLUDED.pb_median,
  net_breaker_count = EXCLUDED.net_breaker_count,
  net_breaker_ratio = EXCLUDED.net_breaker_ratio
"""

TREASURY_UPSERT = """
INSERT INTO treasury_yield (trading_day, yield_10y)
VALUES (%s, %s)
ON CONFLICT (trading_day) DO UPDATE SET yield_10y = EXCLUDED.yield_10y
"""

INDEX_UPSERT = """
INSERT INTO index_valuation_history (trading_day, index_code, index_name, pe, pb, dividend_yield)
VALUES (%s, %s, %s, %s, %s, %s)
ON CONFLICT (trading_day, index_code) DO UPDATE SET
  pe = EXCLUDED.pe, pb = EXCLUDED.pb, dividend_yield = EXCLUDED.dividend_yield
"""

INDUSTRY_UPSERT = """
INSERT INTO industry_valuation (trading_day, industry_code, industry_name, pe, pb, roe, dividend_yield)
VALUES (%s, %s, %s, %s, %s, NULL, NULL)
ON CONFLICT (trading_day, industry_code) DO UPDATE SET
  pe = EXCLUDED.pe, pb = EXCLUDED.pb
"""

MAPPING_UPSERT = """
INSERT INTO shenwan_industry_mapping (stock_code, stock_name, industry_code, industry_name)
VALUES (%s, %s, %s, %s)
ON CONFLICT (stock_code) DO UPDATE SET
  industry_code = EXCLUDED.industry_code, industry_name = EXCLUDED.industry_name
"""


def upsert_snapshot(conn, trading_day: dt.date, snap: dict) -> None:
    with conn.cursor() as cur:
        cur.execute(SNAPSHOT_UPSERT, (trading_day, snap["pe_median"], snap["pb_median"],
                                      snap["net_breaker_count"], snap["net_breaker_ratio"]))
    conn.commit()


def upsert_treasury(conn, trading_day: dt.date, yield_10y: float) -> None:
    with conn.cursor() as cur:
        cur.execute(TREASURY_UPSERT, (trading_day, yield_10y))
    conn.commit()


def upsert_index(conn, trading_day, index_code: str, index_name: str, pe, pb, dividend_yield) -> None:
    with conn.cursor() as cur:
        cur.execute(INDEX_UPSERT, (trading_day, index_code, index_name, pe, pb, dividend_yield))
    conn.commit()


def upsert_industry(conn, trading_day: dt.date, rows: Iterable[dict]) -> None:
    with conn.cursor() as cur:
        for r in rows:
            code = r.get("industry_code") or r["industry_name"]
            cur.execute(INDUSTRY_UPSERT, (trading_day, code, r["industry_name"], r["pe"], r["pb"]))
    conn.commit()


def upsert_mapping(conn, rows: Iterable[tuple]) -> None:
    with conn.cursor() as cur:
        for stock_code, stock_name, industry_code, industry_name in rows:
            cur.execute(MAPPING_UPSERT, (stock_code, stock_name, industry_code, industry_name))
    conn.commit()
