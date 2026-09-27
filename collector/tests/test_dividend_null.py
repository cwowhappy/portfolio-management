"""P0-4 指数股息率估算失败回退 NULL（原 0.0 污染 ERP 输入且无标记）。

已确认设计：回退语义 0.0→NULL（backend ERP 序列已有 null 过滤，该日自动跳过）；
NULL 即标记，不加 estimated 列；fallback 事件进 run.message（源挂 last_warnings，
executor 合入 message、不改变终态）；历史 0.0 行修正 SQL 单独交用户确认执行。
"""

from unittest.mock import MagicMock, patch

import pandas as pd

from collector.executor.executor import Executor
from collector.model.run import STATUS_SUCCESS
from collector.model.task import Collector
from collector.sources.plugins import IndexValuationSource, make_index_dividend_fetch

# ---------------------------------------------------------------- make_index_dividend_fetch


def test_fetch_returns_none_when_upstream_fails(mocker):
    pro = MagicMock()
    with patch("collector.sources.plugins._index_dividend_yield", side_effect=RuntimeError("积分不足")):
        fetch = make_index_dividend_fetch(pro_factory=lambda: pro)
        assert fetch("000300", None, "2026-09-25") is None


def test_fetch_returns_none_on_no_data(mocker):
    pro = MagicMock()
    with patch("collector.sources.plugins._index_dividend_yield", return_value=None):
        fetch = make_index_dividend_fetch(pro_factory=lambda: pro)
        assert fetch("000300", None, "2026-09-25") is None


def test_fetch_returns_value_and_records_fallbacks():
    pro = MagicMock()
    with patch("collector.sources.plugins._index_dividend_yield", side_effect=[2.5, None]):
        fetch = make_index_dividend_fetch(pro_factory=lambda: pro)
        assert fetch("000300", None, None) == 2.5
        assert fetch("000905", None, None) is None
    assert fetch.fallbacks == ["000905"]


def test_explicit_default_still_supported():
    pro = MagicMock()
    with patch("collector.sources.plugins._index_dividend_yield", return_value=None):
        fetch = make_index_dividend_fetch(pro_factory=lambda: pro, default=0.0)
        assert fetch("000300", None, None) == 0.0


# ---------------------------------------------------------------- IndexValuationSource 集成


def _basic_frame():
    return pd.DataFrame(
        {
            "trade_date": ["2026-09-25"],
            "pe": [12.0],
            "pb": [1.3],
        }
    )


def test_source_writes_null_and_warns_on_fallback():
    pro = MagicMock()
    pro.index_dailybasic.return_value = _basic_frame()
    with patch("collector.sources.plugins._index_dividend_yield", side_effect=RuntimeError("积分不足")):
        dividend_fetch = make_index_dividend_fetch(pro_factory=lambda: pro)
        src = IndexValuationSource(
            "idx", pro_factory=lambda: pro, dividend_fetch=dividend_fetch, index_codes={"000300": "沪深300"}
        )
        df = src.fetch({})
    assert df.iloc[0]["dividend_yield"] is None
    assert src.last_warnings and "000300" in src.last_warnings[0]


def test_source_no_warning_when_value_computed():
    pro = MagicMock()
    pro.index_dailybasic.return_value = _basic_frame()
    with patch("collector.sources.plugins._index_dividend_yield", return_value=2.5):
        dividend_fetch = make_index_dividend_fetch(pro_factory=lambda: pro)
        src = IndexValuationSource(
            "idx", pro_factory=lambda: pro, dividend_fetch=dividend_fetch, index_codes={"000300": "沪深300"}
        )
        df = src.fetch({})
    assert df.iloc[0]["dividend_yield"] == 2.5
    assert not src.last_warnings


# ---------------------------------------------------------------- executor 合入 message


class _WarnSource:
    source_id = "idx"

    def fetch(self, params):
        return pd.DataFrame()

    last_warnings = ["dividend_yield 回退 NULL：000300"]


def test_executor_merges_warnings_into_message_without_status_change():
    src = _WarnSource()
    selector = MagicMock()
    selector.select.return_value = [src]
    store = MagicMock()
    store.upsert.return_value = 1
    converter = MagicMock()
    converter.convert.return_value = [{"trading_day": "2026-09-25"}]
    task = Collector(
        "t",
        "t",
        [src],
        converter,
        None,
        target_table="x",
        schedule={},
        validator=None,
        trading_day_gated=False,
    )
    with patch("collector.executor.executor.RunRepository") as run_repo_cls:
        run_repo = run_repo_cls.return_value
        result = Executor(selector, store).run(task, "incremental", {}, conn=MagicMock(), run_id=7)
    assert result.status == STATUS_SUCCESS  # 警告不降级终态（partial 只属于软校验剔行）
    assert result.message == "dividend_yield 回退 NULL：000300"
    assert run_repo.finish_run.call_args.kwargs["message"] == "dividend_yield 回退 NULL：000300"
