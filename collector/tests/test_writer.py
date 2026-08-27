import os
import datetime as dt

import psycopg
import pytest

from collector.store.writer import upsert_snapshot

DB = os.environ.get("DATABASE_URL")


@pytest.mark.skipif(not DB, reason="需要 DATABASE_URL")
def test_upsert_snapshot_idempotent():
    day = dt.date(2026, 8, 27)
    snap = {"pe_median": 19.14, "pb_median": 1.68, "net_breaker_count": 220, "net_breaker_ratio": 0.041}
    with psycopg.connect(DB) as conn:
        try:
            upsert_snapshot(conn, day, snap)
            upsert_snapshot(conn, day, snap)  # 幂等，不报错
            with conn.cursor() as cur:
                cur.execute("SELECT count(*) FROM valuation_snapshot WHERE trading_day = %s", (day,))
                assert cur.fetchone()[0] == 1
        finally:
            with conn.cursor() as cur:
                cur.execute("DELETE FROM valuation_snapshot WHERE trading_day = %s", (day,))
            conn.commit()
