import os

import psycopg
import pytest
from alembic import command
from alembic.config import Config

DB = os.environ.get("DATABASE_URL")

EXPECTED_TABLES = {"collector_task", "collector_task_run", "collector_source_health", "trading_calendar"}


@pytest.mark.skipif(not DB, reason="需要 DATABASE_URL")
def test_upgrade_creates_ops_tables():
    cfg = Config("migrations/alembic.ini")
    cfg.set_main_option("sqlalchemy.url", DB)
    # 隔离：pg_conn fixture 已用 OPS_DDL 建表，这里先清空迁移相关表，再从零跑迁移
    with psycopg.connect(DB) as conn, conn.cursor() as cur:
        cur.execute(
            "DROP TABLE IF EXISTS trading_calendar, collector_source_health,"
            " collector_task_run, collector_task, alembic_version CASCADE"
        )
    command.upgrade(cfg, "head")

    with psycopg.connect(DB) as conn, conn.cursor() as cur:
        cur.execute(
            "SELECT table_name FROM information_schema.tables WHERE table_schema='public'"
            " AND (table_name LIKE 'collector_%' OR table_name='trading_calendar')"
        )
        tables = {r[0] for r in cur.fetchall()}

    assert tables >= EXPECTED_TABLES
