from alembic import op

revision = "0001"
down_revision = None


def upgrade() -> None:
    """collector 运维表基线（v1 发布 squash，2026-09-28）：原 0001~0003 三修订合并为单一基线。

    - 原 0001：collector_task / collector_task_run / collector_source_health / trading_calendar 四表；
    - 原 0002：collector_task_run 加 rows_affected 列（P0-5：DB→DB 源 fetch 内自行 UPDATE、
      走 writer 0 行路径的实际影响行数；rows_written 保持「落库记录数」口径不变，两列分开查询）
      ——基线折入建表，置于表尾 message 之后，与原「建表后 ALTER 追加」的列序完全一致；
    - 原 0003：collector_backfill_progress（P1-7 分片回填进度：逐片记 done/failed，重跑跳过
      已完成片；UNIQUE 键=任务+分片区间，重试同片 upsert 覆盖状态。运维表，无跨服务影响）。
    """
    op.execute("""
        CREATE TABLE collector_task (
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
        CREATE INDEX idx_collector_task_enabled ON collector_task (enabled);

        CREATE TABLE collector_task_run (
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
            message TEXT,
            rows_affected INT
        );
        CREATE INDEX idx_collector_task_run_task ON collector_task_run (task_id, started_at DESC);
        CREATE INDEX idx_collector_task_run_status ON collector_task_run (status);

        CREATE TABLE collector_source_health (
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

        CREATE TABLE trading_calendar (
            trade_date DATE PRIMARY KEY
        );

        CREATE TABLE collector_backfill_progress (
            id BIGSERIAL PRIMARY KEY,
            task_code VARCHAR(64) NOT NULL,
            shard_start DATE NOT NULL,
            shard_end DATE NOT NULL,
            status VARCHAR(16) NOT NULL,
            started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            finished_at TIMESTAMPTZ,
            error TEXT,
            UNIQUE (task_code, shard_start, shard_end)
        );
        CREATE INDEX idx_backfill_progress_task ON collector_backfill_progress (task_code);
    """)


def downgrade() -> None:
    op.execute(
        "DROP TABLE collector_backfill_progress, trading_calendar, collector_source_health, "
        "collector_task_run, collector_task;"
    )
