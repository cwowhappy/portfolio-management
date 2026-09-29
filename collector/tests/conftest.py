"""需要真实 PostgreSQL 的集成测试共享 fixture。

优先用 DATABASE_URL（CI 注入 postgres service）；本地无 DATABASE_URL 时
用 testcontainers 起一次性 postgres:16 容器；docker 不可用时整组 skip。

schema 与生产同源，不再维护手工 DDL（消灭测试与真实迁移的漂移）：
- collector 自己的 alembic 迁移（migrations/，单一基线 0001，squash 自原 0001~0003）：5 张运维表，upgrade 到 head
- 后端 Flyway 迁移 SQL（backend/.../db/migration/ 的 V1/V2/V3）：V1 基线业务目标表 +
  V2 research 域（collector 不消费，仅因 V3 ALTER research_project 需前置重放）+
  V3 intelligence 域（collector 写入 intelligence_news_raw 等 4 张跨服务契约表）
"""

import os
from pathlib import Path

import psycopg
import pytest
from alembic import command
from alembic.config import Config

# 与后端 Testcontainers 约定一致：禁用 Ryuk，兼容 Colima 等无法挂载 docker.sock 的环境
os.environ.setdefault("TESTCONTAINERS_RYUK_DISABLED", "true")

COLLECTOR_DIR = Path(__file__).resolve().parent.parent
# 业务目标表 DDL 由后端 Flyway 管理（跨服务契约），测试直接回放同一份 SQL。
# 2026-09-28 squash 后回放单一基线（原 V3/V4/V7/V13/V15/V18 六文件链；基线自始未建
# 旧 treasury_yield——原 V3 建、V4 迁移后 DROP，终态本无此表，见 v1 发布计划 A1）。
FLYWAY_DIR = COLLECTOR_DIR.parent / "backend" / "src" / "main" / "resources" / "db" / "migration"
FLYWAY_SQL_FILES = ("V1__baseline.sql", "V2__research.sql", "V3__intelligence.sql")

# 30 张表：5 运维（alembic）+ 10 业务目标（Flyway V1 基线）+ 15 情报表（V3，M15）。
# V2 research 表同样被重放建出但 collector 不消费，不进 TRUNCATE 清单。
ALL_TABLES = (
    "collector_task_run",
    "collector_source_health",
    "collector_backfill_progress",
    "collector_task",
    "trading_calendar",
    "valuation_snapshot",
    "industry_valuation",
    "index_valuation_history",
    "shenwan_industry_mapping",
    "treasury_yield_curve",
    "index_constituent",
    "stock_valuation_daily",
    "stock_financial",
    "index_close_history",
    "etf_basic",
    "intelligence_news_raw",
    "intelligence_news_extract",
    "intelligence_announcement",
    "intelligence_announcement_extract",
    "intelligence_policy_raw",
    "intelligence_policy_event",
    "intelligence_macro_series",
    "intelligence_macro_calendar",
    "intelligence_source_switch",
    "intelligence_daily_brief",
    "intelligence_subscription",
    "intelligence_subscription_stock",
    "intelligence_feishu_binding",
    "intelligence_binding_code",
    "intelligence_push_log",
)


@pytest.fixture(scope="session")
def pg_url():
    url = os.environ.get("DATABASE_URL")
    if url:
        yield url
        return
    try:
        try:
            from testcontainers.community.postgres import PostgresContainer
        except ImportError:
            from testcontainers.postgres import PostgresContainer
        container = PostgresContainer("postgres:16")
        container.start()
    except Exception as e:
        pytest.skip(f"需要 Docker 或 DATABASE_URL 运行集成测试: {e}")
    with container:
        host = container.get_container_host_ip()
        port = container.get_exposed_port(5432)
        yield f"postgresql://{container.username}:{container.password}@{host}:{port}/{container.dbname}"


@pytest.fixture(scope="session")
def pg_schema(pg_url):
    """在 pg_url 指向的库上按真实迁移建全量 schema（session 级一次）。

    约定测试库为空库（CI 的 postgres:16 service / 本地一次性容器）；alembic 幂等，
    但 Flyway SQL 无 IF NOT EXISTS，非空库重复建表会报错——属预期暴露而非兜底。
    """
    # (a) collector 自己的运维表：alembic upgrade head（script_location 显式绝对路径，
    # 不依赖 pytest 的 cwd）
    cfg = Config(str(COLLECTOR_DIR / "migrations" / "alembic.ini"))
    cfg.set_main_option("script_location", str(COLLECTOR_DIR / "migrations"))
    cfg.set_main_option("sqlalchemy.url", pg_url)
    command.upgrade(cfg, "head")
    # (b) 业务目标表：按 Flyway 版本顺序回放后端 SQL 文本。两份文件均无 Flyway 占位符，
    # psycopg3 的 execute 支持单次调用内多语句，整段执行即可。
    with psycopg.connect(pg_url) as conn:
        for name in FLYWAY_SQL_FILES:
            conn.execute((FLYWAY_DIR / name).read_text(encoding="utf-8"))
        conn.commit()
    return pg_url


@pytest.fixture()
def pg_conn(pg_schema):
    """函数级连接：schema 由 pg_schema 保证，这里只清数据，不再执行 DDL。"""
    with psycopg.connect(pg_schema) as conn:
        conn.execute(f"TRUNCATE {', '.join(ALL_TABLES)}")
        conn.commit()
        yield conn
