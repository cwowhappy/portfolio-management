"""全量任务 YAML 装配冒烟（L5/L2）与 registry / make_trigger 行为。

用真实 tasks/ 目录 + 真实 build_registries 走完整装配链路：
load_task_defs → build_registries → assemble_collector，全量任务全部装配成功即通过。
Config 用占位值：插件源与 ConfigurableSource 均为惰性构造，装配不触外部 API。
"""

from pathlib import Path

import pytest
from apscheduler.triggers.cron import CronTrigger
from apscheduler.triggers.interval import IntervalTrigger

from collector.config import Config
from collector.scheduler.jobs import assemble_collector, build_registries, load_task_defs, make_trigger
from collector.sources.base import SourceError

TASKS_DIR = Path(__file__).resolve().parent.parent / "tasks"

EXPECTED_TASKS = {
    "all_a_valuation",
    "index_constituent",
    "index_valuation",
    "industry_valuation",
    "shenwan_mapping",
    "stock_financial",
    "stock_valuation_daily",
    "treasury_yield_curve",
}


def _registries():
    return build_registries(Config(database_url="postgresql://placeholder", tushare_token="placeholder"))


def test_load_task_defs_reads_all_yaml():
    defs = load_task_defs(str(TASKS_DIR))
    assert {d["task_code"] for d in defs} == EXPECTED_TASKS


def test_all_yaml_tasks_assemble():
    defs = load_task_defs(str(TASKS_DIR))
    regs = _registries()
    collectors = [assemble_collector(d, regs) for d in defs]
    assert {c.task_code for c in collectors} == EXPECTED_TASKS
    for c in collectors:
        assert c.sources, c.task_code
        assert c.converter is not None
        # 每个任务的调度定义都能构建出触发器
        assert make_trigger(c.schedule) is not None


def test_make_trigger_cron():
    trigger = make_trigger({"type": "cron", "cron": "30 15 * * 1-5"})
    assert isinstance(trigger, CronTrigger)


def test_make_trigger_interval():
    trigger = make_trigger({"type": "interval", "days": 7})
    assert isinstance(trigger, IntervalTrigger)


def test_make_trigger_unknown_type_raises():
    with pytest.raises(ValueError, match="未知调度类型"):
        make_trigger({"type": "hourly"})


def test_registries_get_registered():
    regs = _registries()
    source = regs["source"].get({"source_id": "ak_x", "type": "akshare", "call": "stock_zh_a_spot_em"})
    assert source.source_id == "ak_x"
    plugin = regs["source"].get({"source_id": "p", "type": "plugin", "class": "shenwan_mapping"})
    assert plugin is regs["source"].plugins["shenwan_mapping"]
    assert regs["converter"].get("field_mapping_all_a") is not None
    assert regs["calc"].get("snapshot") is not None
    validator = regs["validator"].get([{"check": "min_rows", "value": 1, "level": "hard"}])
    assert validator is not None


@pytest.mark.parametrize("kind", ["source", "converter", "calc", "validator"])
def test_registries_get_unregistered_raises(kind):
    regs = _registries()
    with pytest.raises(SourceError, match="未注册"):
        if kind == "source":
            regs[kind].get({"source_id": "x", "type": "plugin", "class": "nope"})
        else:
            regs[kind].get("nope")


def test_source_registry_unknown_type_raises():
    regs = _registries()
    with pytest.raises(SourceError, match="未知源类型"):
        regs["source"].get({"source_id": "x", "type": "ftp", "call": "f"})
