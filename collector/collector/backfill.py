"""区间回补（P1-7 增强前为整区间单次执行，中断后重跑重复拉全量）。

现按自然月分片、逐片执行逐片记进度（progress 仓储可选注入）：
- 重跑跳过已完成片（done），失败片重试覆盖（upsert）；
- industry_index_close 等整区间例外任务单片执行（BACKFILL_WHOLE_RANGE_TASKS）；
- 幂等兜底仍是源端 upsert——进度表只省重复拉取，不承担正确性。
"""

import calendar
import datetime as dt
import logging

from collector.model.run import MODE_BACKFILL
from collector.sources.constants import BACKFILL_WHOLE_RANGE_TASKS
from collector.sources.plugins import normalize_date

logger = logging.getLogger(__name__)


def plan_monthly_shards(start: dt.date, end: dt.date) -> list[tuple[dt.date, dt.date]]:
    """按自然月边界切 [start, end]：首尾月可能是不完整月，中间整月。"""
    shards = []
    cur = start
    while cur <= end:
        month_end = dt.date(cur.year, cur.month, calendar.monthrange(cur.year, cur.month)[1])
        shard_end = min(month_end, end)
        shards.append((cur, shard_end))
        cur = shard_end + dt.timedelta(days=1)
    return shards


class BackfillSummary:
    def __init__(self, task_code, total, executed, skipped, last_result=None):
        self.task_code = task_code
        self.total = total
        self.executed = executed
        self.skipped = skipped
        self.last_result = last_result

    def __str__(self):
        return (
            f"backfill {self.task_code}: 分片 {self.total}（执行 {self.executed}、跳过已完成 {self.skipped}）"
            f"（幂等可重跑，中断后续跑只补未完成片）"
        )


def run_backfill(runner, task, start: str, end: str, progress=None):
    unsupported = [s.source_id for s in task.sources if not getattr(s, "supports_range", False)]
    if unsupported:
        raise ValueError(
            f"任务 {task.task_code} 的源 {', '.join(unsupported)} 不支持区间回填（supports_range=False），拒绝 backfill"
        )
    start_date = dt.datetime.strptime(normalize_date(start, "start"), "%Y%m%d").date()
    end_date = dt.datetime.strptime(normalize_date(end, "end"), "%Y%m%d").date()
    if start_date > end_date:
        raise ValueError(f"start({start}) 不能晚于 end({end})")

    if task.task_code in BACKFILL_WHOLE_RANGE_TASKS:
        shards = [(start_date, end_date)]  # 全历史拉取+裁剪语义：分片只会重复拉全量
    else:
        shards = plan_monthly_shards(start_date, end_date)

    done = progress.completed(task.task_code) if progress is not None else set()
    executed = skipped = 0
    last_result = None
    for shard_start, shard_end in shards:
        if (shard_start, shard_end) in done:
            skipped += 1
            logger.info("分片 %s~%s 已完成，跳过", shard_start, shard_end)
            continue
        try:
            last_result = runner.run(
                task,
                mode=MODE_BACKFILL,
                params={"start": shard_start.strftime("%Y%m%d"), "end": shard_end.strftime("%Y%m%d")},
                force=True,
            )
        except Exception as exc:
            if progress is not None:
                progress.record(task.task_code, shard_start, shard_end, "failed", error=str(exc))
            raise
        if progress is not None:
            progress.record(task.task_code, shard_start, shard_end, "done")
        executed += 1
    return BackfillSummary(task.task_code, len(shards), executed, skipped, last_result)
