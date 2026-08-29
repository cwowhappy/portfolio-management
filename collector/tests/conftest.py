"""需要真实 PostgreSQL 的集成测试共享 fixture。

优先用 DATABASE_URL（CI 注入 postgres service）；本地无 DATABASE_URL 时
用 testcontainers 起一次性 postgres:16 容器；docker 不可用时整组 skip。
"""
import os

import psycopg
import pytest

# 与后端 Testcontainers 约定一致：禁用 Ryuk，兼容 Colima 等无法挂载 docker.sock 的环境
os.environ.setdefault("TESTCONTAINERS_RYUK_DISABLED", "true")

# 与 migrations/versions/0001_ops_tables.py 对齐的运维表 + 目标表最小结构
OPS_DDL = """
CREATE TABLE IF NOT EXISTS collector_task (
    id BIGSERIAL PRIMARY KEY,
    task_code VARCHAR(64) NOT NULL UNIQUE,
    task_name VARCHAR(128) NOT NULL,
    source_ids JSONB NOT NULL,
    converter VARCHAR(64) NOT NULL,
    calc VARCHAR(64),
    validator JSONB,
    target_table VARCHAR(64) NOT NULL,
    schedule JSONB NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    trading_day_gated BOOLEAN NOT NULL DEFAULT true,
    retry_max INT NOT NULL DEFAULT 3,
    retry_backoff VARCHAR(16) NOT NULL DEFAULT 'exponential',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS collector_task_run (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL REFERENCES collector_task(id),
    mode VARCHAR(16) NOT NULL DEFAULT 'incremental',
    status VARCHAR(16) NOT NULL,
    source_used VARCHAR(64),
    params JSONB,
    rows_written INT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    error TEXT,
    message TEXT
);
CREATE TABLE IF NOT EXISTS collector_source_health (
    id BIGSERIAL PRIMARY KEY,
    source_id VARCHAR(64) NOT NULL UNIQUE,
    total_runs INT NOT NULL DEFAULT 0,
    success_runs INT NOT NULL DEFAULT 0,
    consecutive_failures INT NOT NULL DEFAULT 0,
    last_latency_ms INT,
    last_success_at TIMESTAMPTZ,
    last_failure_at TIMESTAMPTZ,
    last_error TEXT,
    score NUMERIC(5,2),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS valuation_snapshot (
    trading_day DATE NOT NULL PRIMARY KEY,
    pe_median NUMERIC,
    pb_median NUMERIC,
    net_breaker_count INT,
    net_breaker_ratio NUMERIC
);
"""


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


@pytest.fixture()
def pg_conn(pg_url):
    with psycopg.connect(pg_url) as conn:
        conn.execute(OPS_DDL)
        conn.execute("TRUNCATE collector_task_run, collector_source_health, collector_task, valuation_snapshot")
        conn.commit()
        yield conn
