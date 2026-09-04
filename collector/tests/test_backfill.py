from unittest.mock import MagicMock

import pytest

from collector.backfill import run_backfill
from collector.config import Config
from collector.model.run import MODE_BACKFILL, STATUS_SUCCESS, RunResult
from collector.model.task import Collector
from collector.scheduler.jobs import build_registries


def test_backfill_calls_runner_with_range():
    runner = MagicMock()
    runner.run.return_value = RunResult("t", MODE_BACKFILL, STATUS_SUCCESS)
    task = Collector("t", "t", [], MagicMock(), None, MagicMock(), "x", {})
    run_backfill(runner, task, "2020-01-01", "2026-08-28")
    runner.run.assert_called_once_with(
        task, mode="backfill", params={"start": "20200101", "end": "20260828"}, force=True
    )


def test_backfill_rejects_non_range_source():
    """supports_range=False 的源直接拒绝 backfill 并提示，不静默降级为当天快照。"""
    src = MagicMock()
    src.source_id = "snapshot_only"
    src.supports_range = False
    task = Collector("t", "t", [src], MagicMock(), None, MagicMock(), "x", {})
    with pytest.raises(ValueError, match="不支持区间回填"):
        run_backfill(MagicMock(), task, "2020-01-01", "2020-02-01")


def test_build_registries_has_all_plugins():
    regs = build_registries(Config(database_url="postgresql://x", tushare_token="tok"))
    assert set(regs) == {"source", "converter", "calc", "validator"}
    assert set(regs["source"].plugins) >= {
        "shenwan_mapping",
        "index_valuation",
        "treasury_curve",
        "index_constituent",
        "industry_universe",
        "all_a_spot_backup",
    }
    assert set(regs["converter"].plugins) >= {
        "field_mapping_all_a",
        "field_mapping_index",
        "field_mapping_sw",
        "field_mapping_curve",
        "field_mapping_constituent",
        "field_mapping_industry",
    }
    assert set(regs["calc"].plugins) >= {"snapshot", "industry_weighted"}


def test_index_valuation_plugin_wired_with_dividend_fetch():
    """C-3.2：index_valuation 插件必须装配 dividend_fetch，否则 dividend_yield 恒 None。"""
    from collector.sources.plugins import IndexValuationSource

    regs = build_registries(Config(database_url="postgresql://x", tushare_token="tok"))
    plugin = regs["source"].plugins["index_valuation"]
    assert isinstance(plugin, IndexValuationSource)
    assert plugin.dividend_fetch is not None
