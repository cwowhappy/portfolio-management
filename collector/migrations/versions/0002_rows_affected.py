from alembic import op

revision = "0002"
down_revision = "0001"


def upgrade() -> None:
    # P0-5：DB→DB 源（fetch 内自行 UPDATE，走 writer 0 行路径）的实际影响行数。
    # rows_written 保持「落库记录数」口径不变，两列分开查询。
    op.execute("ALTER TABLE collector_task_run ADD COLUMN rows_affected INT")


def downgrade() -> None:
    op.execute("ALTER TABLE collector_task_run DROP COLUMN rows_affected")
