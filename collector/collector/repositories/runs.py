import json
import logging

logger = logging.getLogger(__name__)

RUN_LIST_COLS = ["started_at", "status", "mode", "source_used", "rows_written", "message", "error"]

LIST_RUNS_SQL = """
SELECT r.started_at, r.status, r.mode, r.source_used, r.rows_written, r.message, r.error
FROM collector_task_run r
JOIN collector_task t ON t.id = r.task_id
WHERE t.task_code = %s
ORDER BY r.started_at DESC
LIMIT %s
"""

# record 在运行结束时调用，started_at 用列默认值、finished_at 记为当前时刻。
RECORD_SQL = """
INSERT INTO collector_task_run (task_id, mode, status, source_used, params, rows_written, error, message, finished_at)
VALUES (%s, %s, %s, %s, %s, %s, %s, %s, now())
RETURNING id
"""

NEVER_SUCCEEDED_SQL = """
SELECT t.task_code FROM collector_task t
WHERE t.task_code = ANY(%s) AND NOT EXISTS (
  SELECT 1 FROM collector_task_run r
  WHERE r.task_id = t.id AND r.status IN ('success', 'partial'))
"""


class RunRepository:
    def __init__(self, conn):
        self.conn = conn

    def list_runs(self, task_code: str, limit: int = 20) -> list[dict]:
        with self.conn.cursor() as cur:
            cur.execute(LIST_RUNS_SQL, (task_code, limit))
            rows = cur.fetchall()
        return [dict(zip(RUN_LIST_COLS, row)) for row in rows]

    def record(self, task_code, mode, status, source_used=None, params=None,
               rows_written=None, error=None, message=None):
        with self.conn.cursor() as cur:
            cur.execute("SELECT id FROM collector_task WHERE task_code=%s", (task_code,))
            row = cur.fetchone()
            if row is None:
                logger.warning("运行记录跳过：未知任务 %s", task_code)
                return None
            task_id = row[0]
            cur.execute(RECORD_SQL, (task_id, mode, status, source_used,
                                     json.dumps(params) if params else None,
                                     rows_written, error, message))
            run_id = cur.fetchone()[0]
        self.conn.commit()
        return run_id

    def never_succeeded(self, task_codes) -> set:
        """从未成功运行过（无 success/partial 记录）的任务，供冷启动补跑。"""
        if not task_codes:
            return set()
        with self.conn.cursor() as cur:
            cur.execute(NEVER_SUCCEEDED_SQL, (list(task_codes),))
            return {r[0] for r in cur.fetchall()}
