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


def test_index_constituent_upsert_deletes_then_inserts_for_affected_indexes():
    """C-9：index_constituent 半年快照先删后插，被调出指数的股票不再残留。"""
    store = Store()
    conn = MagicMock()
    cur = conn.cursor.return_value.__enter__.return_value
    records = [
        {"index_code": "000300", "stock_code": "600519", "stock_name": "贵州茅台", "weight": 5.0},
        {"index_code": "000300", "stock_code": "000001", "stock_name": "平安银行", "weight": 3.0},
        {"index_code": "000905", "stock_code": "600519", "stock_name": "贵州茅台", "weight": 2.0},
    ]
    store.upsert(conn, "index_constituent", records)

    delete_sql = cur.execute.call_args.args[0]
    assert "DELETE FROM index_constituent" in delete_sql
    assert "000300" in str(cur.execute.call_args.args[1])
    assert "000905" in str(cur.execute.call_args.args[1])
    cur.executemany.assert_called_once()  # 删后仍批量插入
    conn.commit.assert_called_once()


def test_non_constituent_table_does_not_delete():
    """非成分股表不受影响：仍是纯 executemany upsert，无 DELETE。"""
    store = Store()
    conn = MagicMock()
    cur = conn.cursor.return_value.__enter__.return_value
    store.upsert(conn, "valuation_snapshot", [{"trading_day": dt.date(2026, 8, 28), "pe_median": 1.0}])
    cur.execute.assert_not_called()
    cur.executemany.assert_called_once()


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
