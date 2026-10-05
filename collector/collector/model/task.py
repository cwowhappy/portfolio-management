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
    # P0-1 fetch 超时（秒）：{source_id: timeout_seconds}，来自 YAML source 条目；
    # 缺省用 executor 的 DEFAULT_FETCH_TIMEOUT_SECONDS。plugin 源是共享单例，
    # 超时配置挂在任务上（按 source_id 查）而非源实例上，避免跨任务串味。
    source_timeouts: dict | None = None
    # C1 任务依赖：上游 task_code 列表；runner 运行时前置检查——上游当日无
    # success/partial run 则 skipped + 告警留痕（不触 executor）。None/空 = 无依赖。
    depends_on: list | None = None
