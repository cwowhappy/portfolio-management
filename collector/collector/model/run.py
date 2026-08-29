from dataclasses import dataclass

STATUS_RUNNING = "running"
STATUS_SUCCESS = "success"
STATUS_PARTIAL = "partial"
STATUS_FAILED = "failed"
STATUS_SKIPPED = "skipped"

MODE_INCREMENTAL = "incremental"
MODE_BACKFILL = "backfill"


@dataclass
class RunResult:
    task_code: str
    mode: str
    status: str
    source_used: str | None = None
    rows_written: int = 0
    message: str | None = None
    error: str | None = None
