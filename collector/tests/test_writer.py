import datetime as dt
import os
from unittest.mock import MagicMock

import psycopg
import pytest

from collector.store.writer import Store

DB = os.environ.get("DATABASE_URL")


def test_upsert_uses_executemany():
    store = Store()
    conn = MagicMock()
    cur = conn.cursor.return_value.__enter__.return_value
    store.upsert(conn, "valuation_snapshot", [
        {"trading_day": dt.date(2026, 8, 28), "pe_median": 15.0, "pb_median": 1.5,
         "net_breaker_count": 10, "net_breaker_ratio": 0.1},
    ])
    cur.executemany.assert_called_once()
    conn.commit.assert_called_once()


@pytest.mark.skipif(not DB, reason="需要 DATABASE_URL")
def test_upsert_idempotent():
    store = Store()
    day = dt.date(2026, 8, 28)
    rec = {"trading_day": day, "pe_median": 15.0, "pb_median": 1.5,
           "net_breaker_count": 10, "net_breaker_ratio": 0.1}
    with psycopg.connect(DB) as conn:
        store.upsert(conn, "valuation_snapshot", [rec])
        store.upsert(conn, "valuation_snapshot", [rec])
        with conn.cursor() as cur:
            cur.execute("SELECT count(*) FROM valuation_snapshot WHERE trading_day=%s", (day,))
            assert cur.fetchone()[0] == 1
