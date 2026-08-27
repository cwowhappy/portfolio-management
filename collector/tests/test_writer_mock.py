"""writer.py upsert 函数的 mock 单测（无需真实数据库）。

与 test_writer.py 的 DB 集成测试互补：这里用 MagicMock 替换 conn/cursor，
断言各 upsert 函数按预期调用 execute/commit，保证 writer.py 在无 DB 环境下
也可被覆盖（覆盖率门禁）。
"""
import datetime as dt
from unittest import mock

from collector.store.writer import (
    INDUSTRY_UPSERT,
    MAPPING_UPSERT,
    TREASURY_UPSERT,
    upsert_industry,
    upsert_mapping,
    upsert_treasury,
)


def test_upsert_treasury_executes_and_commits():
    conn = mock.MagicMock()
    cur = conn.cursor.return_value.__enter__.return_value

    upsert_treasury(conn, dt.date(2026, 8, 27), 2.21)

    cur.execute.assert_called_once_with(TREASURY_UPSERT, (dt.date(2026, 8, 27), 2.21))
    conn.commit.assert_called_once_with()


def test_upsert_industry_executes_each_row_with_name_fallback():
    conn = mock.MagicMock()
    cur = conn.cursor.return_value.__enter__.return_value

    rows = [
        {"industry_code": "801780", "industry_name": "银行", "pe": 5.2, "pb": 0.6},
        # 无 industry_code 时回退 industry_name
        {"industry_name": "食品饮料", "pe": 25.0, "pb": 6.0},
    ]
    upsert_industry(conn, dt.date(2026, 8, 27), rows)

    assert cur.execute.call_count == 2
    cur.execute.assert_has_calls(
        [
            mock.call(
                INDUSTRY_UPSERT,
                (dt.date(2026, 8, 27), "801780", "银行", 5.2, 0.6),
            ),
            mock.call(
                INDUSTRY_UPSERT,
                (dt.date(2026, 8, 27), "食品饮料", "食品饮料", 25.0, 6.0),
            ),
        ]
    )
    conn.commit.assert_called_once_with()


def test_upsert_mapping_executes_each_row():
    conn = mock.MagicMock()
    cur = conn.cursor.return_value.__enter__.return_value

    rows = [
        ("600519", "贵州茅台", "801120", "食品饮料"),
        ("000858", "五粮液", "801120", "食品饮料"),
    ]
    upsert_mapping(conn, rows)

    assert cur.execute.call_count == 2
    cur.execute.assert_has_calls(
        [
            mock.call(MAPPING_UPSERT, ("600519", "贵州茅台", "801120", "食品饮料")),
            mock.call(MAPPING_UPSERT, ("000858", "五粮液", "801120", "食品饮料")),
        ]
    )
    conn.commit.assert_called_once_with()
