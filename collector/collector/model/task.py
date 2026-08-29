from dataclasses import dataclass


@dataclass
class Collector:
    task_code: str
    task_name: str
    sources: list
    converter: object
    calc: object | None
    target_table: str
    schedule: dict
    validator: object | None = None
    enabled: bool = True
    trading_day_gated: bool = True
    retry_max: int = 3
    retry_backoff: str = "exponential"
