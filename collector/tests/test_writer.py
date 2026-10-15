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
    store.upsert(
        conn,
        "valuation_snapshot",
        [
            {
                "trading_day": dt.date(2026, 8, 28),
                "pe_median": 1.0,
                "pb_median": 1.5,
                "net_breaker_count": 0,
                "net_breaker_ratio": 0.0,
            }
        ],
    )
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


def test_upsert_missing_required_column_fails_fast():
    """C2：白名单外缺列（converter/calc 改名漏列场景）→ StoreError，且不落任何行。"""
    store = Store()
    conn = MagicMock()
    rec = {
        "trading_day": dt.date(2026, 8, 28),
        "stock_code": "600519",
        "stock_name": "贵州茅台",
        "pe_ttm": 22.5,
        "pb": 7.8,
        # close 缺失：字段改名漏列场景，必须 fail-fast 而非静默写 NULL
        "dividend_yield": 2.1,
        "total_mv": 2.1e12,
        "circ_mv": 2.1e12,
        "turnover_rate": 0.35,
    }
    with pytest.raises(StoreError, match="缺列"):
        store.upsert(conn, "stock_valuation_daily", [rec])
    conn.cursor.assert_not_called()  # 写库前就拒绝
    conn.commit.assert_not_called()


def test_upsert_keyset_check_is_union_not_first_row():
    """C6-P3-6（2026-10-15 审查）：键集校验升级为全量并集——不再依赖「首行代表性」：
    首行缺列但后续行携带，即视为批次具备该列（O(n) 一遍并集；现有 producer 均同质，
    此处锁并集语义，消除只查首行的理论缝）。"""
    store = Store()
    conn = MagicMock()
    full = {
        "trading_day": dt.date(2026, 8, 28),
        "stock_code": "600519",
        "stock_name": "贵州茅台",
        "pe_ttm": 22.5,
        "pb": 7.8,
        "dividend_yield": 2.1,
        "total_mv": 2.1e12,
        "circ_mv": 2.1e12,
        "turnover_rate": 0.35,
        "close": 1500.0,
    }
    first_broken = {k: v for k, v in full.items() if k != "close"}
    assert store.upsert(conn, "stock_valuation_daily", [first_broken, full]) == 2
    conn.cursor.assert_called()  # 并集具备全部必需列 → 不拒绝、正常写入


def test_upsert_idempotent(pg_conn):
    store = Store()
    day = dt.date(2026, 8, 28)
    rec = {"trading_day": day, "pe_median": 15.0, "pb_median": 1.5, "net_breaker_count": 10, "net_breaker_ratio": 0.1}
    store.upsert(pg_conn, "valuation_snapshot", [rec])
    store.upsert(pg_conn, "valuation_snapshot", [rec])
    with pg_conn.cursor() as cur:
        cur.execute("SELECT count(*) FROM valuation_snapshot WHERE trading_day=%s", (day,))
        assert cur.fetchone()[0] == 1
