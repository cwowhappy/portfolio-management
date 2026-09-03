import datetime as dt
import logging
import time

import psycopg

from collector.executor.executor import AllSourcesFailed, StoreError
from collector.model.run import STATUS_FAILED, STATUS_SKIPPED, RunResult
from collector.repositories.runs import RunRepository

logger = logging.getLogger(__name__)

BACKOFF_BASE_SECONDS = 30
STRATEGY_EXPONENTIAL = "exponential"
STRATEGY_FIXED = "fixed"

# hashtextextended 比 crc32 冲突率低得多，且由 DB 侧计算，跨进程一致。
LOCK_SQL = "SELECT pg_try_advisory_lock(hashtextextended(%s, 0))"
UNLOCK_SQL = "SELECT pg_advisory_unlock(hashtextextended(%s, 0))"


def backoff_delays(strategy: str, count: int) -> list[int]:
    """按策略生成退避序列：exponential = 30×2ⁿ 秒，fixed = 固定 30 秒。"""
    if strategy == STRATEGY_FIXED:
        return [BACKOFF_BASE_SECONDS] * count
    if strategy == STRATEGY_EXPONENTIAL:
        return [BACKOFF_BASE_SECONDS * (2**i) for i in range(count)]
    raise ValueError(f"未知退避策略: {strategy}")


def run_date(params) -> dt.date:
    """运行目标日期：用户指定 --date 时用该日期做交易日门控，否则用当天。"""
    raw = (params or {}).get("date")
    if not raw:
        return dt.date.today()
    try:
        return dt.date.fromisoformat(str(raw))
    except ValueError as e:
        raise ValueError(f"非法日期参数 date={raw!r}，期望 YYYY-MM-DD") from e


class TaskRunner:
    def __init__(self, database_url, calendar, executor, retry_max=3):
        self.database_url = database_url
        self.calendar = calendar
        self.executor = executor
        self.retry_max = retry_max  # task 未配置 retry_max 时的兜底

    def run(self, task, mode="incremental", params=None, force=False):
        params = params or {}
        day = run_date(params)
        if task.trading_day_gated and not force and not self.calendar.is_trading_day(day):
            logger.info("任务 %s 跳过：%s 非交易日", task.task_code, day)
            self._record_skip(task, mode, params, "非交易日")
            return RunResult(task.task_code, mode, STATUS_SKIPPED, message="非交易日")

        retry_max = getattr(task, "retry_max", None)
        if retry_max is None:
            retry_max = self.retry_max
        delays = backoff_delays(getattr(task, "retry_backoff", None) or STRATEGY_EXPONENTIAL, retry_max)
        last_error = None
        for attempt in range(retry_max + 1):
            # 每次尝试新建连接、用完即弃：上一次失败的连接可能处于 aborted
            # transaction 状态，复用会让重试第一个查询就失败。
            with psycopg.connect(self.database_url) as conn:
                acquired = conn.execute(LOCK_SQL, (task.task_code,)).fetchone()[0]
                if not acquired:
                    logger.info("任务 %s 跳过：上一实例运行中", task.task_code)
                    RunRepository(conn).record(
                        task.task_code, mode, STATUS_SKIPPED, params=params, message="上一实例运行中"
                    )
                    return RunResult(task.task_code, mode, STATUS_SKIPPED, message="上一实例运行中")
                # FR-10：执行前置 running 行，executor 成功/失败时回填 finished_at 与终态；
                # 未知任务（start_run 返回 None）则退回 executor 的旧 record 路径。
                run_id = RunRepository(conn).start_run(task.task_code, mode, params)
                try:
                    # 熔断计数按任务运行而非尝试次数：重试不再放大 consecutive_failures。
                    return self.executor.run(task, mode, params, conn, count_failures=(attempt == 0), run_id=run_id)
                except (AllSourcesFailed, StoreError) as e:
                    last_error = e
                    if attempt >= retry_max:
                        break
                    delay = delays[attempt]
                    logger.warning("任务 %s 第 %d 次运行失败：%s；%ds 后重试", task.task_code, attempt + 1, e, delay)
                    time.sleep(delay)
                except Exception as e:
                    # converter 等原生异常逃逸不会触发 executor 的 finish_run：这里兜底把 running 行
                    # 置为 failed，避免留下永远 running 的悬挂记录（FR-10 状态可观测）。
                    self._abort_run(conn, run_id, e)
                    raise
                finally:
                    conn.execute(UNLOCK_SQL, (task.task_code,))
        logger.error("任务 %s 重试 %d 次后仍失败：%s", task.task_code, retry_max, last_error)
        raise last_error

    def _record_skip(self, task, mode, params, message):
        """skipped（非交易日）也落一条 task_run，满足 FR-10「每次执行记录」。"""
        with psycopg.connect(self.database_url) as conn:
            RunRepository(conn).record(task.task_code, mode, STATUS_SKIPPED, params=params, message=message)

    def _abort_run(self, conn, run_id, error):
        """意外异常逃逸时兜底把 running 行置 failed；连接可能处于 aborted，失败只记日志不掩盖原异常。"""
        if run_id is None:
            return
        try:
            RunRepository(conn).finish_run(run_id, STATUS_FAILED, error=str(error), message="执行异常")
        except Exception:
            logger.exception("任务运行异常后回填 failed 状态失败（run_id=%s）", run_id)
