import json


class RunRepository:
    def __init__(self, conn):
        self.conn = conn

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
