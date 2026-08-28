import pytest

from collector.sources.base import SourceError
from collector.validators.rules import RuleValidator


def test_required_hard_raises():
    v = RuleValidator([{"field": "code", "check": "required", "level": "hard"}])
    with pytest.raises(SourceError):
        v.validate([{"name": "x"}])


def test_range_soft_drops_outliers():
    v = RuleValidator([{"field": "pe", "check": "range", "min": 0, "max": 100, "level": "soft"}])
    records, issues = v.validate([{"pe": 20.0}, {"pe": 200.0}])
    assert records == [{"pe": 20.0}]
    assert len(issues) == 1


def test_min_rows_hard():
    v = RuleValidator([{"check": "min_rows", "value": 5, "level": "hard"}])
    with pytest.raises(SourceError):
        v.validate([{"pe": 1.0}])
