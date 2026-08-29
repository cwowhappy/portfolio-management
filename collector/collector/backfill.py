from collector.model.run import MODE_BACKFILL
from collector.sources.plugins import normalize_date


def run_backfill(runner, task, start: str, end: str):
    unsupported = [s.source_id for s in task.sources if not getattr(s, "supports_range", False)]
    if unsupported:
        raise ValueError(
            f"任务 {task.task_code} 的源 {', '.join(unsupported)} 不支持区间回填（supports_range=False），拒绝 backfill"
        )
    return runner.run(
        task,
        mode=MODE_BACKFILL,
        params={"start": normalize_date(start, "start"), "end": normalize_date(end, "end")},
        force=True,
    )
