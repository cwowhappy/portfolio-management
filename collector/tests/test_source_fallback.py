"""C-3.1 回归：all_a_valuation 单源降级到 tushare 备源。

akshare stock_zh_a_spot_em 失败时，executor 应切到 all_a_spot_backup（tushare daily_basic），
且备源输出列与 field_mapping_all_a 的「代码/名称/市盈率-动态/市净率/总市值」映射兼容，
同一 converter + snapshot calc 无需区分来源即可消费。
"""

from unittest.mock import MagicMock, patch

import pandas as pd
import pytest

from collector.converters.field_mapping import FieldMappingConverter
from collector.executor.executor import Executor
from collector.executor.selector import SourceSelector
from collector.model.task import Collector
from collector.sources.configurable import ConfigurableSource
from collector.sources.plugins import AllASpotBackupSource


@pytest.fixture
def repos():
    with (
        patch("collector.executor.executor.HealthRepository") as H,
        patch("collector.executor.executor.RunRepository") as R,
    ):
        hr = MagicMock()
        hr.get.return_value = {}
        H.return_value = hr
        rr = MagicMock()
        R.return_value = rr
        yield rr


def _all_a_converter():
    return FieldMappingConverter(
        {
            "code": {"from": "代码", "type": "str"},
            "name": {"from": "名称", "type": "str"},
            "pe": {"from": "市盈率-动态", "type": "numeric"},
            "pb": {"from": "市净率", "type": "numeric"},
            "market_cap": {"from": "总市值", "type": "numeric"},
        }
    )


def _mock_pro(daily, basic):
    pro = MagicMock()
    pro.daily_basic.return_value = daily
    pro.stock_basic.return_value = basic
    return pro


def test_backup_source_outputs_akshare_compatible_columns():
    """备源输出列须与 akshare spot 同构，field_mapping_all_a 才能直接消费。"""
    daily = pd.DataFrame(
        {"ts_code": ["600519.SH", "000001.SZ"], "pe_ttm": [30.0, 6.0], "pb": [8.0, 0.8], "total_mv": [2.0e6, 2.0e5]}
    )
    basic = pd.DataFrame({"ts_code": ["600519.SH", "000001.SZ"], "name": ["贵州茅台", "平安银行"]})
    src = AllASpotBackupSource("all_a_spot_backup", pro_factory=lambda: _mock_pro(daily, basic))
    df = src.fetch({})
    assert list(df.columns) == ["代码", "名称", "市盈率-动态", "市净率", "总市值"]
    rec = _all_a_converter().convert(df)
    assert rec[0]["code"] == "600519"
    assert rec[0]["name"] == "贵州茅台"
    assert rec[0]["pe"] == 30.0
    assert rec[0]["pb"] == 8.0


def test_executor_falls_back_to_tushare_when_akshare_fails(mocker, repos):
    """akshare 主源抛错 → 降级到 all_a_spot_backup，result.source_used 为备源。"""
    import collector.sources.configurable as cfg

    # akshare 主源失败
    mocker.patch.object(cfg.ak, "stock_zh_a_spot_em", side_effect=RuntimeError("akshare 断连"))
    primary = ConfigurableSource("akshare_spot_em", "akshare", "stock_zh_a_spot_em")

    daily = pd.DataFrame(
        {"ts_code": ["600519.SH", "000001.SZ"], "pe_ttm": [30.0, 6.0], "pb": [8.0, 0.8], "total_mv": [2.0e6, 2.0e5]}
    )
    basic = pd.DataFrame({"ts_code": ["600519.SH", "000001.SZ"], "name": ["贵州茅台", "平安银行"]})
    backup = AllASpotBackupSource("all_a_spot_backup", pro_factory=lambda: _mock_pro(daily, basic))

    from collector.calc.snapshot import SnapshotCalc

    task = Collector(
        task_code="all_a_valuation",
        task_name="全A估值快照",
        sources=[primary, backup],
        converter=_all_a_converter(),
        calc=SnapshotCalc(),
        validator=None,
        target_table="valuation_snapshot",
        schedule={},
        enabled=True,
    )
    store = mocker.MagicMock()
    store.upsert.return_value = 1
    ex = Executor(SourceSelector(), store)
    res = ex.run(task, "incremental", {}, mocker.MagicMock())
    assert res.status == "success"
    assert res.source_used == "all_a_spot_backup"
