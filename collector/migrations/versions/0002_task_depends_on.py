import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects.postgresql import JSONB

revision = "0002"
down_revision = "0001"


def upgrade() -> None:
    """MS-28 P2-C1：collector_task 加 depends_on（JSONB，task_code 列表）。

    任务间依赖从「cron 时间顺序注释」升级为显式声明；runner 运行时前置检查——
    上游当日无 success/partial run 则记 skipped + 告警留痕（不触 executor）。
    可空列：无依赖任务存 NULL（YAML 中显式 depends_on: null）。
    """
    op.add_column("collector_task", sa.Column("depends_on", JSONB(), nullable=True))


def downgrade() -> None:
    op.drop_column("collector_task", "depends_on")
