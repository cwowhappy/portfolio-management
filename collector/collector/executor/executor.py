import datetime as dt
import time

from collector.model.health import SourceHealth
from collector.model.run import MODE_INCREMENTAL, STATUS_FAILED, STATUS_PARTIAL, STATUS_SUCCESS, RunResult
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

    def _finish_or_record(
        self,
        run_repo,
        task,
        mode,
        status,
        run_id,
        *,
        params=None,
        source_used=None,
        rows_written=None,
        error=None,
        message=None,
        record_kwargs=None,
    ):
        """有 run_id（TaskRunner 已插 running 前置行）则回填该行；否则按旧逻辑新插一行。

        record_kwargs 用于精确复刻旧 record 调用的关键字集合，避免多传 None 参数改变调用签名。
        """
        if run_id is not None:
            run_repo.finish_run(
                run_id, status, source_used=source_used, rows_written=rows_written, error=error, message=message
            )
            return
        kwargs = {
            "params": params,
            "source_used": source_used,
            "rows_written": rows_written,
            "error": error,
            "message": message,
        }
        if record_kwargs is not None:
            kwargs = {k: v for k, v in kwargs.items() if k in record_kwargs}
        run_repo.record(task.task_code, mode, status, **kwargs)

    def run(self, task, mode=MODE_INCREMENTAL, params=None, conn=None, count_failures=True, run_id=None):
        params = params or {}
        health_repo = HealthRepository(conn)
        run_repo = RunRepository(conn)
        source_ids = [s.source_id for s in task.sources]
        health = health_repo.get(source_ids)
        candidates = self.selector.select(task.sources, health)

        if not candidates:
            msg = "所有源熔断或不可用"
            self._finish_or_record(
                run_repo,
                task,
                mode,
                STATUS_FAILED,
                run_id,
                params=params,
                message=msg,
                record_kwargs={"params", "message"},
            )
            return RunResult(task.task_code, mode, STATUS_FAILED, message=msg)

        for src in candidates:
            started = time.monotonic()
            h = health.setdefault(src.source_id, SourceHealth(src.source_id))
            try:
                try:
                    raw = src.fetch(params)
                except SourceError:
                    raise
                except Exception as e:
                    # 数据源库（akshare/tushare 等）抛的是各自的原生异常（如 aiohttp 断连），
                    # 统一转成 SourceError，才能被下面的 except 捕获进入换源/降级。
                    raise SourceError(f"源 {src.source_id} 取数失败: {e}") from e
                records = task.converter.convert(raw)
                # 先校验再聚合：min_rows 等规则基于原始明细行数（如 ~5000 行快照），
                # 若在 calc 之后校验，快照已被折叠成 1 行导致校验必然失败。
                if task.validator is not None:
                    records, issues = task.validator.validate(records)
                else:
                    issues = []
                if task.calc is not None:
                    try:
                        records = task.calc.compute(records)
                    except Exception as e:
                        # calc 缺列等 KeyError 不能裸抛：逃逸后不会有 failed run 记录，
                        # 包成 SourceError 才能走降级/记录路径。
                        raise SourceError(f"calc 计算失败: {e}") from e
                day = _trading_day(params)
                for r in records:
                    r.setdefault("trading_day", day)
                try:
                    rows = self.store.upsert(conn, task.target_table, records)
                except StoreError as e:
                    self._finish_or_record(
                        run_repo,
                        task,
                        mode,
                        STATUS_FAILED,
                        run_id,
                        params=params,
                        error=str(e),
                        record_kwargs={"params", "error"},
                    )
                    raise
                latency = int((time.monotonic() - started) * 1000)
                health_repo.save(self.selector.record_success(h, latency))
                status = STATUS_PARTIAL if issues else STATUS_SUCCESS
                msg = "; ".join(issues) or None
                self._finish_or_record(
                    run_repo,
                    task,
                    mode,
                    status,
                    run_id,
                    params=params,
                    source_used=src.source_id,
                    rows_written=rows,
                    message=msg,
                    record_kwargs={"params", "source_used", "rows_written", "message"},
                )
                return RunResult(
                    task.task_code,
                    mode,
                    status,
                    source_used=src.source_id,
                    rows_written=rows,
                    message=msg,
                )
            except SourceError as e:
                if count_failures:
                    health_repo.save(self.selector.record_failure(h, str(e)))
                continue

        self._finish_or_record(
            run_repo,
            task,
            mode,
            STATUS_FAILED,
            run_id,
            params=params,
            error="AllSourcesFailed",
            record_kwargs={"params", "error"},
        )
        raise AllSourcesFailed(task.task_code)
