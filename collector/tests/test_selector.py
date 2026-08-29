from datetime import UTC, datetime, timedelta

from collector.executor.selector import SourceSelector
from collector.model.health import SourceHealth


def _src(sid):
    class S:
        source_id = sid

        def fetch(self, params): ...

    return S()


def test_cold_start_uses_declared_priority():
    sel = SourceSelector()
    out = sel.select([_src("a"), _src("b")], {})
    assert [s.source_id for s in out] == ["a", "b"]


def test_higher_score_first():
    sel = SourceSelector()
    ha = SourceHealth("a", total_runs=10, success_runs=10, score=90.0)
    hb = SourceHealth("b", total_runs=10, success_runs=6, score=60.0)
    out = sel.select([_src("a"), _src("b")], {"a": ha, "b": hb})
    assert [s.source_id for s in out] == ["a", "b"]


def test_open_circuit_skipped():
    sel = SourceSelector()
    now = datetime.now(UTC)
    ha = SourceHealth(
        "a", total_runs=3, success_runs=0, consecutive_failures=3, last_failure_at=now - timedelta(seconds=10)
    )
    out = sel.select([_src("a"), _src("b")], {"a": ha})
    assert [s.source_id for s in out] == ["b"]


def test_half_open_allows_probe():
    """半开源可探针，但健康候选优先于探针。"""
    sel = SourceSelector()
    now = datetime.now(UTC)
    ha = SourceHealth(
        "a", total_runs=3, success_runs=0, consecutive_failures=3, last_failure_at=now - timedelta(seconds=700)
    )
    out = sel.select([_src("a"), _src("b")], {"a": ha})
    assert [s.source_id for s in out] == ["b", "a"]


def test_half_open_probe_used_when_no_healthy_candidate():
    sel = SourceSelector()
    now = datetime.now(UTC)
    ha = SourceHealth(
        "a", total_runs=3, success_runs=0, consecutive_failures=3, last_failure_at=now - timedelta(seconds=700)
    )
    out = sel.select([_src("a")], {"a": ha})
    assert [s.source_id for s in out] == ["a"]


def test_record_failure_resets_on_success():
    sel = SourceSelector()
    h = SourceHealth("a")
    h = sel.record_failure(h, "x")
    h = sel.record_failure(h, "x")
    assert h.consecutive_failures == 2
    h = sel.record_success(h, 100)
    assert h.consecutive_failures == 0
