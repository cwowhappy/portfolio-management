from unittest.mock import MagicMock

from collector.scheduler.jobs import assemble_collector, seed_tasks
from collector.sources.base import SourceError


def test_assemble_collector_wires_registries():
    src_reg, conv_reg, calc_reg, val_reg = MagicMock(), MagicMock(), MagicMock(), MagicMock()
    src_reg.get.side_effect = lambda spec: f"src:{spec['call']}"
    conv_reg.get.return_value = "conv"
    val_reg.get.return_value = "val"
    row = {
        "task_code": "t", "task_name": "T",
        "source_ids": [{"source_id": "a", "type": "akshare", "call": "f"}],
        "converter": "field_mapping", "calc": None,
        "validator": [{"check": "min_rows", "value": 5, "level": "hard"}],
        "target_table": "x", "schedule": {"type": "cron", "cron": "30 15 * * 1-5"},
        "enabled": True, "trading_day_gated": True, "retry_max": 3, "retry_backoff": "exponential",
    }
    regs = {"source": src_reg, "converter": conv_reg, "calc": calc_reg, "validator": val_reg}
    c = assemble_collector(row, regs)
    assert c.sources == ["src:f"]
    assert c.target_table == "x"


def test_seed_tasks_upserts():
    conn = MagicMock()
    seed_tasks(conn, [{"task_code": "t", "task_name": "T", "source_ids": [], "converter": "c",
                       "calc": None, "validator": None, "target_table": "x",
                       "schedule": {}, "enabled": True, "trading_day_gated": True,
                       "retry_max": 3, "retry_backoff": "exponential"}])
    conn.cursor.return_value.__enter__.return_value.execute.assert_called()
    conn.commit.assert_called()


def test_assemble_collector_validator_none_ok():
    src_reg, conv_reg, calc_reg, val_reg = MagicMock(), MagicMock(), MagicMock(), MagicMock()
    src_reg.get.return_value = "src"
    conv_reg.get.return_value = "conv"
    # validator 应可选：若被调用则抛错，证明 assemble_collector 不会触碰 validator 注册表
    val_reg.get.side_effect = SourceError("不应调用 validator 注册表")
    row = {
        "task_code": "t", "task_name": "T",
        "source_ids": [], "converter": "field_mapping", "calc": None,
        "validator": None, "target_table": "x", "schedule": {},
        "enabled": True, "trading_day_gated": True, "retry_max": 3, "retry_backoff": "exponential",
    }
    regs = {"source": src_reg, "converter": conv_reg, "calc": calc_reg, "validator": val_reg}
    c = assemble_collector(row, regs)
    assert c.validator is None
    val_reg.get.assert_not_called()
