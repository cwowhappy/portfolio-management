"""P1-7 backfill 分片进度仓储（collector_backfill_progress，运维表）。"""

RECORD_SQL = """
INSERT INTO collector_backfill_progress (task_code, shard_start, shard_end, status, finished_at, error)
VALUES (%s, %s, %s, %s, now(), %s)
ON CONFLICT (task_code, shard_start, shard_end)
DO UPDATE SET status = EXCLUDED.status, started_at = now(), finished_at = now(), error = EXCLUDED.error
"""

COMPLETED_SQL = """
SELECT shard_start, shard_end FROM collector_backfill_progress
WHERE task_code = %s AND status = 'done'
"""


class BackfillProgressRepository:
    def __init__(self, conn):
        self.conn = conn

    def completed(self, task_code) -> set[tuple]:
        """已成功完成的分片区间集合（仅 done 计入跳过；failed 重试）。"""
        with self.conn.cursor() as cur:
            cur.execute(COMPLETED_SQL, (task_code,))
            return {row[:2] for row in cur.fetchall()}

    def record(self, task_code, shard_start, shard_end, status, error=None) -> None:
        with self.conn.cursor() as cur:
            cur.execute(RECORD_SQL, (task_code, shard_start, shard_end, status, error))
        self.conn.commit()
