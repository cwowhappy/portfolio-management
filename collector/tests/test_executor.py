import datetime as dt
from unittest.mock import MagicMock, patch

import pytest

from collector.executor.executor import AllSourcesFailed, Executor, StoreError
from collector.executor.selector import SourceSelector
from collector.model.task import Collector
from collector.sources.base import SourceError
from collector.validators.rules import RuleValidator


def _task(sources):
    conv = MagicMock()
    conv.convert.return_value = [{"code": "a"}]
    val = MagicMock()
    val.validate.return_value = ([{"code": "a"}], [])
    return Collector(
        "t", "t", sources, conv, None, target_table="valuation_snapshot", schedule={}, validator=val, enabled=True
    )


def _source(sid, fail=False):
    s = MagicMock()
    s.source_id = sid
    s.supports_range = False
    if fail:
        s.fetch.side_effect = SourceError("boom")
    else:
        s.fetch.return_value = MagicMock()
    return s


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


def test_primary_success(repos):
    store = MagicMock()
    store.upsert.return_value = 5
    ex = Executor(SourceSelector(), store)
    res = ex.run(_task([_source("a")]), "incremental", {}, MagicMock())
    assert res.status == "success"
    assert res.source_used == "a"


def test_fallback_to_secondary(repos):
    store = MagicMock()
    ex = Executor(SourceSelector(), store)
    res = ex.run(_task([_source("a", fail=True), _source("b")]), "incremental", {}, MagicMock())
    assert res.status == "success"
    assert res.source_used == "b"


def test_non_source_error_fetch_treated_as_source_failure(repos):
    """数据源库抛原生异常（如 aiohttp 断连）应被转成 SourceError 并触发降级。"""
    store = MagicMock()
    ex = Executor(SourceSelector(), store)
    a = _source("a")
    a.fetch.side_effect = RuntimeError("Server disconnected")
    b = _source("b")
    res = ex.run(_task([a, b]), "incremental", {}, MagicMock())
    assert res.status == "success"
    assert res.source_used == "b"


def test_all_sources_failed_raises(repos):
    ex = Executor(SourceSelector(), MagicMock())
    with pytest.raises(AllSourcesFailed):
        ex.run(_task([_source("a", fail=True)]), "incremental", {}, MagicMock())


def test_store_error_not_fallback(repos):
    store = MagicMock()
    store.upsert.side_effect = StoreError("db down")
    ex = Executor(SourceSelector(), store)
    with pytest.raises(StoreError):
        ex.run(_task([_source("a")]), "incremental", {}, MagicMock())


def _agg_task(records):
    conv = MagicMock()
    conv.convert.return_value = records
    val = MagicMock()
    val.validate.side_effect = lambda recs: (recs, [])
    return Collector(
        "t",
        "t",
        [_source("a")],
        conv,
        None,
        target_table="valuation_snapshot",
        schedule={},
        validator=val,
        enabled=True,
    )


def test_aggregate_record_gets_trading_day_injected(repos):
    store = MagicMock()
    store.upsert.return_value = 1
    ex = Executor(SourceSelector(), store)
    ex.run(_agg_task([{"code": "a"}]), "incremental", {"date": "2026-08-28"}, MagicMock())
    records = store.upsert.call_args.args[2]
    assert records[0]["trading_day"] == dt.date(2026, 8, 28)


def test_existing_trading_day_preserved(repos):
    store = MagicMock()
    store.upsert.return_value = 1
    ex = Executor(SourceSelector(), store)
    ex.run(
        _agg_task([{"code": "a", "trading_day": dt.date(2026, 8, 1)}]),
        "incremental",
        {"date": "2026-08-28"},
        MagicMock(),
    )
    records = store.upsert.call_args.args[2]
    assert records[0]["trading_day"] == dt.date(2026, 8, 1)


def test_validate_runs_before_calc(repos):
    """validate 必须先于 calc：min_rows 基于原始明细行数，calc 折叠后必然失败。"""
    conv = MagicMock()
    conv.convert.return_value = [{"code": f"a{i}"} for i in range(5)]
    calc = MagicMock()
    calc.compute.side_effect = lambda recs: recs[:1]
    task = Collector(
        "t",
        "t",
        [_source("a")],
        conv,
        calc,
        target_table="valuation_snapshot",
        schedule={},
        validator=RuleValidator([{"check": "min_rows", "value": 2, "level": "hard"}]),
        enabled=True,
    )
    store = MagicMock()
    store.upsert.return_value = 1
    ex = Executor(SourceSelector(), store)
    res = ex.run(task, "incremental", {}, MagicMock())
    assert res.status == "success"
    calc.compute.assert_called_once()
    assert len(store.upsert.call_args.args[2]) == 1


def test_all_sources_failed_records_failed_run(repos):
    ex = Executor(SourceSelector(), MagicMock())
    with pytest.raises(AllSourcesFailed):
        ex.run(_task([_source("a", fail=True)]), "incremental", {}, MagicMock())
    repos.record.assert_called_once_with("t", "incremental", "failed", error="AllSourcesFailed", params={})


def test_store_error_records_failed_run(repos):
    store = MagicMock()
    store.upsert.side_effect = StoreError("db down")
    ex = Executor(SourceSelector(), store)
    with pytest.raises(StoreError):
        ex.run(_task([_source("a")]), "incremental", {}, MagicMock())
    repos.record.assert_called_once_with("t", "incremental", "failed", error="db down", params={})


# ---------------------------------------------------------------- 熔断计数 / calc 异常收敛


def test_count_failures_false_skips_health_record():
    """重试尝试（count_failures=False）不再累计 consecutive_failures。"""
    with patch("collector.executor.executor.HealthRepository") as H, patch("collector.executor.executor.RunRepository"):
        hr = H.return_value
        hr.get.return_value = {}
        ex = Executor(SourceSelector(), MagicMock())
        with pytest.raises(AllSourcesFailed):
            ex.run(_task([_source("a", fail=True)]), "incremental", {}, MagicMock(), count_failures=False)
        hr.save.assert_not_called()


def test_calc_error_wrapped_and_failed_run_recorded(repos):
    """calc 的 KeyError 等异常收敛为 SourceError，最终记 failed run 而非裸逃逸。"""
    conv = MagicMock()
    conv.convert.return_value = [{"code": "a"}]
    calc = MagicMock()
    calc.compute.side_effect = KeyError("industry_code")
    task = Collector("t", "t", [_source("a")], conv, calc, target_table="valuation_snapshot", schedule={}, enabled=True)
    ex = Executor(SourceSelector(), MagicMock())
    with pytest.raises(AllSourcesFailed):
        ex.run(task, "incremental", {}, MagicMock())
    repos.record.assert_called_once_with("t", "incremental", "failed", error="AllSourcesFailed", params={})


# ---------------------------------------------------------------- 全源熔断终端分支


def test_no_candidates_records_failed_run_without_raising(repos):
    """selector 返回空候选（所有源熔断/不可用）时记 failed run 并返回，不抛 AllSourcesFailed。"""
    selector = MagicMock()
    selector.select.return_value = []
    ex = Executor(selector, MagicMock())
    res = ex.run(_task([_source("a")]), "incremental", {}, MagicMock())
    assert res.status == "failed"
    assert res.message == "所有源熔断或不可用"
    repos.record.assert_called_once_with("t", "incremental", "failed", message="所有源熔断或不可用", params={})
