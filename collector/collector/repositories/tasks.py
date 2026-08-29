import json

TASK_COLS = ["task_code", "task_name", "source_ids", "converter", "calc", "validator",
             "target_table", "schedule", "enabled", "trading_day_gated", "retry_max", "retry_backoff"]

UPSERT_TASK = """
INSERT INTO collector_task (task_code, task_name, source_ids, converter, calc, validator,
  target_table, schedule, enabled, trading_day_gated, retry_max, retry_backoff)
VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
ON CONFLICT (task_code) DO UPDATE SET
  task_name=EXCLUDED.task_name, source_ids=EXCLUDED.source_ids, converter=EXCLUDED.converter,
  calc=EXCLUDED.calc, validator=EXCLUDED.validator, target_table=EXCLUDED.target_table,
  schedule=EXCLUDED.schedule, trading_day_gated=EXCLUDED.trading_day_gated,
  retry_max=EXCLUDED.retry_max, retry_backoff=EXCLUDED.retry_backoff
"""


def _parse_json(v):
    """psycopg3 默认把 JSONB 返回为已解析的 list/dict；mock 则返回 JSON 字符串。两者都兼容。"""
    if v is None:
        return None
    if isinstance(v, str):
        return json.loads(v)
    return v


def _parse_row(row):
    d = dict(zip(TASK_COLS, row))
    d["source_ids"] = _parse_json(d["source_ids"])
    d["validator"] = _parse_json(d["validator"])
    d["schedule"] = _parse_json(d["schedule"])
    return d


class TaskRepository:
    def __init__(self, conn):
        self.conn = conn

    def list_enabled(self):
        with self.conn.cursor() as cur:
            cur.execute(f"SELECT {','.join(TASK_COLS)} FROM collector_task WHERE enabled")
            rows = cur.fetchall()
        return [_parse_row(row) for row in rows]

    def get(self, task_code: str) -> dict | None:
        with self.conn.cursor() as cur:
            cur.execute(
                f"SELECT {','.join(TASK_COLS)} FROM collector_task WHERE task_code=%s",
                (task_code,),
            )
            row = cur.fetchone()
        return _parse_row(row) if row else None

    def upsert(self, task: dict):
        with self.conn.cursor() as cur:
            cur.execute(UPSERT_TASK, tuple(
                task.get(c) if not isinstance(task.get(c), (list, dict)) else json.dumps(task.get(c))
                for c in TASK_COLS
            ))
        self.conn.commit()
