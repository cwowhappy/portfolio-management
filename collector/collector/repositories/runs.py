import json

RUN_LIST_COLS = ["started_at", "status", "mode", "source_used", "rows_written", "message", "error"]

LIST_RUNS_SQL = """
SELECT r.started_at, r.status, r.mode, r.source_used, r.rows_written, r.message, r.error
FROM collector_task_run r
JOIN collector_task t ON t.id = r.task_id
WHERE t.task_code = %s
ORDER BY r.started_at DESC
LIMIT %s
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
                return None
            task_id = row[0]
            cur.execute("""
                INSERT INTO collector_task_run (task_id, mode, status, source_used, params, rows_written, error, message)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
                RETURNING id
            """, (task_id, mode, status, source_used, json.dumps(params) if params else None,
                  rows_written, error, message))
            run_id = cur.fetchone()[0]
        self.conn.commit()
        return run_id
