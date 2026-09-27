from alembic import op

revision = "0003"
down_revision = "0002"


def upgrade() -> None:
    # P1-7 backfill 分片进度：逐片记 done/failed，重跑跳过已完成片；UNIQUE 键=任务+分片区间，
    # 重试同片 upsert 覆盖状态。运维表（collector 自有），无跨服务影响。
    op.execute("""
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
    op.execute("DROP TABLE collector_backfill_progress")
