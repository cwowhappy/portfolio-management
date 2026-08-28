from dataclasses import dataclass, field


@dataclass
class Collector:
    task_code: str
    task_name: str
    sources: list
    converter: object
    calc: object | None
    validator: object
    target_table: str
    schedule: dict
    enabled: bool = True
    trading_day_gated: bool = True
    retry_max: int = 3
    retry_backoff: str = "exponential"
