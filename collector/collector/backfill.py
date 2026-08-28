from collector.model.run import MODE_BACKFILL


def run_backfill(runner, task, start: str, end: str):
    return runner.run(task, mode=MODE_BACKFILL, params={"start": start, "end": end}, force=True)
