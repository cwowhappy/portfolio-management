"""collector 迁移测试：运维表从空状态 upgrade head 后全部落齐。

复用 conftest 的 pg_url（本地 testcontainers / CI DATABASE_URL 均可），不再依赖
DATABASE_URL 环境变量，本地可真实执行。

取舍说明：session 级 pg_schema fixture 已把整库迁到 head，无法再断言「全空库首次
升级」。这里改为先 DROP 运维相关表 + alembic_version 再重新 upgrade head，仍覆盖
「迁移脚本本身能从零建出全部运维表」这一关键断言，且不动 Flyway 管的业务表。
由于 upgrade 会把库恢复到 head，用例在 session 内任意顺序执行都安全。
"""

from pathlib import Path

import psycopg
from alembic import command
from alembic.config import Config

COLLECTOR_DIR = Path(__file__).resolve().parent.parent

EXPECTED_TABLES = {"collector_task", "collector_task_run", "collector_source_health", "trading_calendar"}


def test_upgrade_creates_ops_tables(pg_url):
    cfg = Config(str(COLLECTOR_DIR / "migrations" / "alembic.ini"))
    cfg.set_main_option("script_location", str(COLLECTOR_DIR / "migrations"))
    cfg.set_main_option("sqlalchemy.url", pg_url)
    # 隔离：清掉迁移相关表与版本表，再从零跑迁移（无论 pg_schema 是否已执行过）
    with psycopg.connect(pg_url) as conn, conn.cursor() as cur:
        cur.execute(
            "DROP TABLE IF EXISTS trading_calendar, collector_source_health,"
            " collector_task_run, collector_task, alembic_version CASCADE"
        )
    command.upgrade(cfg, "head")

    with psycopg.connect(pg_url) as conn, conn.cursor() as cur:
        cur.execute(
            "SELECT table_name FROM information_schema.tables WHERE table_schema='public'"
            " AND (table_name LIKE 'collector_%' OR table_name='trading_calendar')"
        )
        tables = {r[0] for r in cur.fetchall()}

    assert tables >= EXPECTED_TABLES
