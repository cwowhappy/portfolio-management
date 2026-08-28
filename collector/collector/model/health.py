from dataclasses import dataclass
from datetime import datetime


@dataclass
class SourceHealth:
    source_id: str
    total_runs: int = 0
    success_runs: int = 0
    consecutive_failures: int = 0
    last_latency_ms: int | None = None
    last_success_at: datetime | None = None
    last_failure_at: datetime | None = None
    last_error: str | None = None
    score: float = 50.0
