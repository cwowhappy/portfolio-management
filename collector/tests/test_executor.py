import datetime as dt
from unittest.mock import MagicMock, patch

import pytest

from collector.executor.executor import Executor, AllSourcesFailed, StoreError
from collector.executor.selector import SourceSelector
from collector.model.task import Collector
from collector.sources.base import SourceError


def _task(sources):
    conv = MagicMock()
    conv.convert.return_value = [{"code": "a"}]
    val = MagicMock()
    val.validate.return_value = ([{"code": "a"}], [])
    return Collector("t", "t", sources, conv, None, val, "valuation_snapshot", {}, enabled=True)


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
    with patch("collector.executor.executor.HealthRepository") as H, \
         patch("collector.executor.executor.RunRepository") as R:
        hr = MagicMock()
        hr.get.return_value = {}
        H.return_value = hr
        rr = MagicMock()
        R.return_value = rr
        yield


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
    return Collector("t", "t", [_source("a")], conv, None, val, "valuation_snapshot", {}, enabled=True)


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
    ex.run(_agg_task([{"code": "a", "trading_day": dt.date(2026, 8, 1)}]),
           "incremental", {"date": "2026-08-28"}, MagicMock())
    records = store.upsert.call_args.args[2]
    assert records[0]["trading_day"] == dt.date(2026, 8, 1)
