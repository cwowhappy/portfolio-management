import datetime as dt
import time
import zlib

import psycopg

from collector.executor.executor import AllSourcesFailed, StoreError
from collector.model.run import RunResult, STATUS_SKIPPED


def _lock_key(task_code: str) -> int:
    return zlib.crc32(task_code.encode("utf-8"))


class TaskRunner:
    def __init__(self, database_url, calendar, executor, retry_max=3, backoff=(30, 60, 120)):
        self.database_url = database_url
        self.calendar = calendar
        self.executor = executor
        self.retry_max = retry_max
        self.backoff = backoff

    def run(self, task, mode="incremental", params=None, force=False):
        if task.trading_day_gated and not force and not self.calendar.is_trading_day(dt.date.today()):
            return RunResult(task.task_code, mode, STATUS_SKIPPED, message="非交易日")

        with psycopg.connect(self.database_url) as conn:
            key = _lock_key(task.task_code)
            acquired = conn.execute("SELECT pg_try_advisory_lock(%s)", (key,)).fetchone()[0]
            if not acquired:
                return RunResult(task.task_code, mode, STATUS_SKIPPED, message="上一实例运行中")
            try:
                return self._run_with_retry(conn, task, mode, params)
            finally:
                conn.execute("SELECT pg_advisory_unlock(%s)", (key,))

    def _run_with_retry(self, conn, task, mode, params):
        for attempt in range(self.retry_max):
            try:
                return self.executor.run(task, mode, params, conn)
            except (AllSourcesFailed, StoreError) as e:
                if attempt == self.retry_max - 1:
                    raise
                delay = self.backoff[attempt] if attempt < len(self.backoff) else self.backoff[-1]
                time.sleep(delay)
