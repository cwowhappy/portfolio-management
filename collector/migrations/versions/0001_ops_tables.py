from alembic import op

revision = "0001"
down_revision = None


def upgrade() -> None:
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
            message TEXT
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
    """)


def downgrade() -> None:
    op.execute("DROP TABLE trading_calendar, collector_source_health, collector_task_run, collector_task;")
