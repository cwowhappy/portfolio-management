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

# Ryuk 收割启用（2026-10-16 探针+源码调研，矩阵与机制全文见 docs/technology/architecture/
# testcontainers-ryuk-colima-兼容矩阵调研.md）：禁用态下客户端被 kill 容器即永久泄漏
# （曾积累 288 个；另有 with container: 双重启动根因，见下方 fixture 注释）。两个注入缺一不可：
# - socket override 指向 Docker VM（Colima/Desktop）内部标准路径。宿主侧 socket 转发文件
#   永远挂不进 VM（virtiofs 下 mkdir operation not supported，colima#997），故此值用普通
#   赋值强制覆盖——外部宿主路径值（如曾配 ~/.zshrc 的 colima 转发路径）恒错，不应被
#   setdefault 尊重；/var/run/docker.sock 对原生 Linux 为同值 no-op。
# - 默认 ryuk 0.8.1 的收割受 TC-py#1114 注册竞态影响（ryuk 未就绪时 connect 被 docker-proxy
#   先 accept、session 过滤器行丢失 → ryuk clients=0 空剪，表现为随机漏收），且其 ImagesPrune
#   在 Docker 29 containerd 存储下破坏并发拉取（TC-py#1131）；钉 0.13.0——本机 SIGKILL 探针
#   5/5，与 0.14.0 Go 代码零差异。变量名是 RYUK_CONTAINER_IMAGE（config.py 单例实例化时急切
#   快照；TC 首次导入在 fixture 内、晚于本文件模块级语句，时序成立），setdefault 保留外部覆盖。
#   TC-py#1124（connect+读 ACK 重试）合并后可移除本钉版。
# CI 注入 DATABASE_URL 走 service 容器不触 testcontainers。
os.environ["TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE"] = "/var/run/docker.sock"
os.environ.setdefault("RYUK_CONTAINER_IMAGE", "testcontainers/ryuk:0.13.0")

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
    # 显式 start + finally stop，禁止 `with container:`——DockerContainer.__enter__ 会再调一次
    # start()（无条件 create），双重启动下 teardown 只删 _container 指向的第二个，第一个每会话
    # 泄漏一个（288 个存量容器的根因，2026-10-16 docker events 实证：每轮恰好 +1 个 Up 容器，
    # 且与 Ryuk 启停无关）。
    try:
        host = container.get_container_host_ip()
        port = container.get_exposed_port(5432)
        yield f"postgresql://{container.username}:{container.password}@{host}:{port}/{container.dbname}"
    finally:
        container.stop()


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
    # (b) 业务目标表：按 Flyway 版本顺序回放后端 SQL 文本。三份文件均无 Flyway 占位符，
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
