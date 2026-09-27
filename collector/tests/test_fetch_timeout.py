"""P0-1 executor 通用 fetch 超时。

已确认设计：全局默认 1800s（DEFAULT_FETCH_TIMEOUT_SECONDS），YAML source 条目
timeout_seconds 覆盖（长任务 stock_financial=5400 / etf_basic=2400）；超时抛
SourceError 走既有换源语义；backfill 模式豁免（区间回补耗时不可预估）。
实现用 daemon 线程包裹：超时后放弃等待，线程随上游断开自然消亡（Python 无法强杀线程）。
"""

import time
from unittest.mock import MagicMock

import pytest

from collector.executor.executor import DEFAULT_FETCH_TIMEOUT_SECONDS, Executor, _fetch_with_timeout
from collector.model.run import MODE_BACKFILL, MODE_INCREMENTAL, STATUS_SUCCESS
from collector.model.task import Collector
from collector.scheduler.jobs import assemble_collector
from collector.sources.base import Source, SourceError


class _Src(Source):
    def __init__(self, source_id, value=None, error=None, delay=0.0):
        self.source_id = source_id
        self.value = value
        self.error = error
        self.delay = delay

    def fetch(self, params):
        time.sleep(self.delay)
        if self.error is not None:
            raise self.error
        return self.value


# ---------------------------------------------------------------- _fetch_with_timeout 单元


def test_fetch_timeout_raises_source_error():
    src = _Src("s", value=1, delay=0.5)
    with pytest.raises(SourceError, match="fetch 超时"):
        _fetch_with_timeout(src, {}, timeout_seconds=0.1)


def test_fetch_within_timeout_returns_value():
    src = _Src("s", value="frame", delay=0.05)
    assert _fetch_with_timeout(src, {}, timeout_seconds=5) == "frame"


def test_fetch_timeout_none_disables_wrap():
    src = _Src("s", value="frame", delay=0.3)
    assert _fetch_with_timeout(src, {}, timeout_seconds=None) == "frame"


def test_fetch_native_exception_passthrough():
    src = _Src("s", error=ValueError("原生异常"))
    with pytest.raises(ValueError, match="原生异常"):
        _fetch_with_timeout(src, {}, timeout_seconds=5)


def test_default_timeout_is_1800s():
    assert DEFAULT_FETCH_TIMEOUT_SECONDS == 1800


# ---------------------------------------------------------------- executor 集成


def _executor(sources):
    selector = MagicMock()
    selector.select.return_value = sources
    store = MagicMock()
    store.upsert.return_value = 2
    converter = MagicMock()
    converter.convert.return_value = [{"trading_day": "2026-09-25"}]
    return Executor(selector, store), converter


def _task(sources, converter, timeouts=None):
    return Collector(
        "t",
        "t",
        sources,
        converter,
        None,
        target_table="x",
        schedule={},
        validator=None,
        trading_day_gated=False,
        source_timeouts=timeouts,
    )


def test_executor_timeout_falls_back_to_next_source():
    sleepy = _Src("slow", delay=0.5)
    good = _Src("good", value="frame")
    executor, converter = _executor([sleepy, good])
    task = _task([sleepy, good], converter, timeouts={"slow": 0.1, "good": 5})
    result = executor.run(task, MODE_INCREMENTAL, {}, conn=MagicMock())
    assert result.status == STATUS_SUCCESS
    assert result.source_used == "good"


def test_executor_backfill_mode_exempt_from_timeout():
    sleepy = _Src("slow", value="frame", delay=0.3)
    executor, converter = _executor([sleepy])
    task = _task([sleepy], converter, timeouts={"slow": 0.1})
    result = executor.run(task, MODE_BACKFILL, {}, conn=MagicMock())
    assert result.status == STATUS_SUCCESS
    assert result.source_used == "slow"


# ---------------------------------------------------------------- assemble 提取 + 契约


def test_assemble_extracts_source_timeouts():
    registries = {
        "source": MagicMock(),
        "converter": MagicMock(),
        "calc": MagicMock(),
        "validator": MagicMock(),
    }
    row = {
        "task_code": "t",
        "task_name": "t",
        "source_ids": [
            {"source_id": "a", "type": "plugin", "class": "x", "timeout_seconds": 5400},
            {"source_id": "b", "type": "plugin", "class": "y"},
        ],
        "converter": "c",
        "calc": None,
        "validator": None,
        "target_table": "x",
        "schedule": {},
        "enabled": True,
        "trading_day_gated": False,
        "retry_max": 3,
        "retry_backoff": "exponential",
    }
    task = assemble_collector(row, registries)
    assert task.source_timeouts == {"a": 5400}


def test_long_task_yamls_declare_timeout_override():
    """长任务 YAML 必须显式覆盖默认 1800s（防误杀 stock_financial/etf_basic）。"""
    import yaml

    for path, expected in (("tasks/stock_financial.yaml", 5400), ("tasks/etf_basic.yaml", 2400)):
        with open(path) as f:
            defs = yaml.safe_load(f)
        timeouts = [s.get("timeout_seconds") for s in defs["source_ids"]]
        assert expected in timeouts, f"{path} 缺少 timeout_seconds={expected}"
