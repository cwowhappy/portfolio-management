"""规范 T5：UPSERT_SQL 与 TABLE_COLUMNS 双份定义的一致性兜底。"""

import re

from collector.store.writer import TABLE_COLUMNS, UPSERT_SQL

_INSERT_COLS = re.compile(r"INSERT INTO\s+(\w+)\s*\(([^)]+)\)", re.IGNORECASE)


def _insert_columns(sql: str) -> tuple[str, list[str]]:
    """从 UPSERT SQL 中解析目标表名与 INSERT 列清单。"""
    m = _INSERT_COLS.search(sql)
    assert m, f"无法从 SQL 解析 INSERT 列: {sql!r}"
    cols = [c.strip() for c in m.group(2).split(",")]
    return m.group(1), cols


def test_upsert_sql_and_table_columns_have_same_keys():
    assert set(UPSERT_SQL) == set(TABLE_COLUMNS)


def test_upsert_sql_insert_columns_match_table_columns():
    for table, sql in UPSERT_SQL.items():
        target, cols = _insert_columns(sql)
        assert target == table, f"UPSERT_SQL 键 {table} 与 SQL 目标表 {target} 不一致"
        assert cols == TABLE_COLUMNS[table], f"表 {table}: SQL 列 {cols} 与 TABLE_COLUMNS {TABLE_COLUMNS[table]} 不一致"
