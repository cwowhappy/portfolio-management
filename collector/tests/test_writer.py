import datetime as dt
from unittest.mock import MagicMock

import psycopg
import pytest

from collector.executor.executor import StoreError
from collector.store.writer import Store


def test_upsert_uses_executemany():
    store = Store()
    conn = MagicMock()
    cur = conn.cursor.return_value.__enter__.return_value
    store.upsert(
        conn,
        "valuation_snapshot",
        [
            {
                "trading_day": dt.date(2026, 8, 28),
                "pe_median": 15.0,
                "pb_median": 1.5,
                "net_breaker_count": 10,
                "net_breaker_ratio": 0.1,
            },
        ],
    )
    cur.executemany.assert_called_once()
    conn.commit.assert_called_once()


def test_upsert_empty_records_returns_zero_without_touching_db():
    """空 records 直通返回 0，不建游标、不提交。"""
    conn = MagicMock()
    assert Store().upsert(conn, "valuation_snapshot", []) == 0
    conn.cursor.assert_not_called()
    conn.commit.assert_not_called()


def test_upsert_psycopg_error_becomes_store_error():
    store = Store()
    conn = MagicMock()
    cur = conn.cursor.return_value.__enter__.return_value
    cur.executemany.side_effect = psycopg.OperationalError("connection broken")
    with pytest.raises(StoreError):
        store.upsert(
            conn,
            "valuation_snapshot",
            [
                {
                    "trading_day": dt.date(2026, 8, 28),
                    "pe_median": 15.0,
                    "pb_median": 1.5,
                    "net_breaker_count": 10,
                    "net_breaker_ratio": 0.1,
                },
            ],
        )
    conn.rollback.assert_called_once()
    conn.commit.assert_not_called()


def test_upsert_idempotent(pg_conn):
    store = Store()
    day = dt.date(2026, 8, 28)
    rec = {"trading_day": day, "pe_median": 15.0, "pb_median": 1.5, "net_breaker_count": 10, "net_breaker_ratio": 0.1}
    store.upsert(pg_conn, "valuation_snapshot", [rec])
    store.upsert(pg_conn, "valuation_snapshot", [rec])
    with pg_conn.cursor() as cur:
        cur.execute("SELECT count(*) FROM valuation_snapshot WHERE trading_day=%s", (day,))
        assert cur.fetchone()[0] == 1
