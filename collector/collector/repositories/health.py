from collector.model.health import SourceHealth


class HealthRepository:
    def __init__(self, conn):
        self.conn = conn

    def get(self, source_ids):
        if not source_ids:
            return {}
        with self.conn.cursor() as cur:
            cur.execute(
                "SELECT source_id, total_runs, success_runs, consecutive_failures, last_latency_ms,"
                " last_success_at, last_failure_at, last_error, score FROM collector_source_health"
                " WHERE source_id = ANY(%s)",
                (list(source_ids),),
            )
            rows = cur.fetchall()
        return {r[0]: SourceHealth(*r) for r in rows}

    def save(self, h: SourceHealth):
        with self.conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO collector_source_health
                  (source_id, total_runs, success_runs, consecutive_failures, last_latency_ms,
                   last_success_at, last_failure_at, last_error, score)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
                ON CONFLICT (source_id) DO UPDATE SET
                  total_runs=EXCLUDED.total_runs, success_runs=EXCLUDED.success_runs,
                  consecutive_failures=EXCLUDED.consecutive_failures,
                  last_latency_ms=EXCLUDED.last_latency_ms, last_success_at=EXCLUDED.last_success_at,
                  last_failure_at=EXCLUDED.last_failure_at, last_error=EXCLUDED.last_error,
                  score=EXCLUDED.score, updated_at=now()
            """,
                (
                    h.source_id,
                    h.total_runs,
                    h.success_runs,
                    h.consecutive_failures,
                    h.last_latency_ms,
                    h.last_success_at,
                    h.last_failure_at,
                    h.last_error,
                    h.score,
                ),
            )
        self.conn.commit()
