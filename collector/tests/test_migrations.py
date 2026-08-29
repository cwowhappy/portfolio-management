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
    command.upgrade(cfg, "head")

    with psycopg.connect(DB) as conn:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND (table_name LIKE 'collector_%' OR table_name='trading_calendar')"
            )
            tables = {r[0] for r in cur.fetchall()}

    assert EXPECTED_TABLES <= tables
