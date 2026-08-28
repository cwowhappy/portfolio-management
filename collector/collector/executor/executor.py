import datetime as dt
import time

from collector.model.health import SourceHealth
from collector.model.run import RunResult, MODE_INCREMENTAL, STATUS_SUCCESS, STATUS_PARTIAL, STATUS_FAILED
from collector.repositories.health import HealthRepository
from collector.repositories.runs import RunRepository
from collector.sources.base import SourceError


class StoreError(Exception):
    """目标侧错误（DB 写入失败），不换源，由 TaskRunner 整任务重试。"""


class AllSourcesFailed(Exception):
    pass


def _trading_day(params):
    raw = params.get("date")
    if raw:
        return dt.date.fromisoformat(str(raw))
    return dt.date.today()


class Executor:
    def __init__(self, selector, store):
        self.selector = selector
        self.store = store

    def run(self, task, mode=MODE_INCREMENTAL, params=None, conn=None):
        params = params or {}
        health_repo = HealthRepository(conn)
        run_repo = RunRepository(conn)
        source_ids = [s.source_id for s in task.sources]
        health = health_repo.get(source_ids)
        candidates = self.selector.select(task.sources, health)

        if not candidates:
            msg = "所有源熔断或不可用"
            run_repo.record(task.task_code, mode, STATUS_FAILED, message=msg, params=params)
            return RunResult(task.task_code, mode, STATUS_FAILED, message=msg)

        for src in candidates:
            started = time.monotonic()
            h = health.setdefault(src.source_id, SourceHealth(src.source_id))
            try:
                raw = src.fetch(params)
                records = task.converter.convert(raw)
                if task.calc is not None:
                    records = task.calc.compute(records)
                day = _trading_day(params)
                for r in records:
                    r.setdefault("trading_day", day)
                if task.validator is not None:
                    records, issues = task.validator.validate(records)
                else:
                    issues = []
                rows = self.store.upsert(conn, task.target_table, records)
                latency = int((time.monotonic() - started) * 1000)
                health_repo.save(self.selector.record_success(h, latency))
                status = STATUS_PARTIAL if issues else STATUS_SUCCESS
                run_repo.record(task.task_code, mode, status, source_used=src.source_id,
                                params=params, rows_written=rows, message="; ".join(issues) or None)
                return RunResult(task.task_code, mode, status, source_used=src.source_id,
                                 rows_written=rows, message="; ".join(issues) or None)
            except SourceError as e:
                health_repo.save(self.selector.record_failure(h, str(e)))
                continue

        raise AllSourcesFailed(task.task_code)
