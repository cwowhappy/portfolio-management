import datetime as dt
import json
import logging

logger = logging.getLogger(__name__)

RUN_LIST_COLS = ["started_at", "status", "mode", "source_used", "rows_written", "rows_affected", "message", "error"]

LIST_RUNS_SQL = """
SELECT r.started_at, r.status, r.mode, r.source_used, r.rows_written, r.rows_affected, r.message, r.error
FROM collector_task_run r
JOIN collector_task t ON t.id = r.task_id
WHERE t.task_code = %s
ORDER BY r.started_at DESC
LIMIT %s
"""

# record 在运行结束时调用，started_at 用列默认值、finished_at 记为当前时刻。
RECORD_SQL = """
INSERT INTO collector_task_run
    (task_id, mode, status, source_used, params, rows_written, rows_affected, error, message, finished_at)
VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, now())
RETURNING id
"""

# start_run 在执行前插入 running 前置行：started_at 取列默认 now()、finished_at 留空，
# 由 finish_run 在执行结束时回填，使单次执行记录同时具备起止时间（时长可算）。
START_RUN_SQL = """
INSERT INTO collector_task_run (task_id, mode, status, params)
SELECT id, %s, 'running', %s FROM collector_task WHERE task_code=%s
RETURNING id
"""

FINISH_RUN_SQL = """
UPDATE collector_task_run
SET status=%s, source_used=%s, rows_written=%s, rows_affected=%s, error=%s, message=%s, finished_at=now()
WHERE id=%s
"""

LATEST_RUN_STATUS_SQL = """
SELECT DISTINCT ON (t.task_code) t.task_code, r.started_at, r.status
FROM collector_task t
LEFT JOIN collector_task_run r ON r.task_id = t.id
ORDER BY t.task_code, r.started_at DESC NULLS LAST
"""

NEVER_SUCCEEDED_SQL = """
SELECT t.task_code FROM collector_task t
WHERE t.task_code = ANY(%s) AND NOT EXISTS (
  SELECT 1 FROM collector_task_run r
  WHERE r.task_id = t.id AND r.status IN ('success', 'partial'))
"""

# C1 depends_on 前置检查：join 形态照 LIST_RUNS_SQL（task_code 在 collector_task，
# run 状态在 collector_task_run，经 task_id FK 关联）；success/partial 口径照
# never_succeeded 先例——partial 也已落数据，视为上游已就绪。
SUCCEEDED_ON_SQL = """
SELECT DISTINCT t.task_code
FROM collector_task_run r
JOIN collector_task t ON t.id = r.task_id
WHERE t.task_code = ANY(%s) AND r.status IN ('success', 'partial') AND r.started_at::date = %s
"""

# C2 悬挂 running reaper：进程在 start_run（事务1）落行后、finish_run（事务3）回填前被 kill
# 时 running 行永久悬挂（runner._abort_run 兜底只覆盖异常逃逸，覆盖不了 kill）。
# error 追加 reaper 文案（CONCAT+COALESCE 保留原错误供排查）；finished_at 照 FINISH_RUN_SQL 回填 now()。
REAP_STALE_RUNNING_SQL = """
UPDATE collector_task_run
SET status='failed',
    error=CONCAT(COALESCE(error, ''), 'reaper: stale running (process killed?)'),
    finished_at=now()
WHERE status='running' AND started_at < %s
"""


class RunRepository:
    def __init__(self, conn):
        self.conn = conn

    def list_runs(self, task_code: str, limit: int = 20) -> list[dict]:
        with self.conn.cursor() as cur:
            cur.execute(LIST_RUNS_SQL, (task_code, limit))
            rows = cur.fetchall()
        return [dict(zip(RUN_LIST_COLS, row, strict=True)) for row in rows]

    def record(
        self,
        task_code,
        mode,
        status,
        source_used=None,
        params=None,
        rows_written=None,
        rows_affected=None,
        error=None,
        message=None,
    ):
        with self.conn.cursor() as cur:
            cur.execute("SELECT id FROM collector_task WHERE task_code=%s", (task_code,))
            row = cur.fetchone()
            if row is None:
                logger.warning("运行记录跳过：未知任务 %s", task_code)
                return None
            task_id = row[0]
            cur.execute(
                RECORD_SQL,
                (
                    task_id,
                    mode,
                    status,
                    source_used,
                    json.dumps(params) if params else None,
                    rows_written,
                    rows_affected,
                    error,
                    message,
                ),
            )
            run_id = cur.fetchone()[0]
        self.conn.commit()
        return run_id

    def start_run(self, task_code, mode, params=None) -> int | None:
        """执行前插入 running 前置行，返回 run_id；未知任务返回 None。"""
        with self.conn.cursor() as cur:
            cur.execute(
                START_RUN_SQL,
                (mode, json.dumps(params) if params else None, task_code),
            )
            row = cur.fetchone()
        self.conn.commit()
        if row is None:
            logger.warning("运行记录跳过：未知任务 %s", task_code)
            return None
        return row[0]

    def finish_run(
        self, run_id, status, source_used=None, rows_written=None, rows_affected=None, error=None, message=None
    ) -> None:
        """执行结束时回填 running 行：置状态并写 finished_at=now()（提供时长）。"""
        if run_id is None:
            return
        with self.conn.cursor() as cur:
            cur.execute(
                FINISH_RUN_SQL,
                (status, source_used, rows_written, rows_affected, error, message, run_id),
            )
        self.conn.commit()

    def latest_run_status(self) -> dict[str, tuple]:
        """每个 task_code 最近一次 task_run 的 (started_at, status)；无运行记录为 (None, None)。"""
        with self.conn.cursor() as cur:
            cur.execute(LATEST_RUN_STATUS_SQL)
            return {row[0]: (row[1], row[2]) for row in cur.fetchall()}

    def never_succeeded(self, task_codes) -> set:
        """从未成功运行过（无 success/partial 记录）的任务，供冷启动补跑。"""
        if not task_codes:
            return set()
        with self.conn.cursor() as cur:
            cur.execute(NEVER_SUCCEEDED_SQL, (list(task_codes),))
            return {r[0] for r in cur.fetchall()}

    def succeeded_on(self, task_codes, day) -> set:
        """day 当日（started_at::date）已有 success/partial run 的任务集合，供 depends_on 前置检查。"""
        if not task_codes:
            return set()
        with self.conn.cursor() as cur:
            cur.execute(SUCCEEDED_ON_SQL, (list(task_codes), day))
            return {r[0] for r in cur.fetchall()}

    def reap_stale_running(self, before: dt.datetime) -> int:
        """C2：把 started_at 早于 before 的悬挂 running 行批量置 failed，返回 reap 数。

        启动时一次性执行（非周期 reaper）；cutoff 一天，刚启动的长任务不被误杀。
        """
        with self.conn.cursor() as cur:
            cur.execute(REAP_STALE_RUNNING_SQL, (before,))
            reaped = cur.rowcount
        self.conn.commit()
        return reaped
