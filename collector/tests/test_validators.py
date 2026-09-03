import pytest

from collector.sources.base import SourceError
from collector.validators.rules import RuleValidator


def test_required_hard_raises():
    v = RuleValidator([{"field": "code", "check": "required", "level": "hard"}])
    with pytest.raises(SourceError):
        v.validate([{"name": "x"}])


def test_required_soft_drops_missing():
    v = RuleValidator([{"field": "code", "check": "required", "level": "soft"}])
    records, issues = v.validate([{"code": "a"}, {"name": "x"}])
    assert records == [{"code": "a"}]
    assert len(issues) == 1


def test_range_soft_drops_outliers():
    v = RuleValidator([{"field": "pe", "check": "range", "min": 0, "max": 100, "level": "soft"}])
    records, issues = v.validate([{"pe": 20.0}, {"pe": 200.0}])
    assert records == [{"pe": 20.0}]
    assert len(issues) == 1


def test_min_rows_hard():
    v = RuleValidator([{"check": "min_rows", "value": 5, "level": "hard"}])
    with pytest.raises(SourceError):
        v.validate([{"pe": 1.0}])


def test_min_rows_soft_appends_issue_without_raising():
    """soft 级别不抛错、不丢记录，只追加 issue（由 executor 记 partial）。"""
    v = RuleValidator([{"check": "min_rows", "value": 5, "level": "soft"}])
    records, issues = v.validate([{"pe": 1.0}])
    assert records == [{"pe": 1.0}]
    assert issues == ["min_rows: 1 < 5"]


# ---------------------------------------------------------------- C-4 补齐内建规则


def test_not_null_hard_raises_on_missing_field():
    v = RuleValidator([{"field": "roe", "check": "not_null", "level": "hard"}])
    with pytest.raises(SourceError):
        v.validate([{"roe": 1.0}, {"roe": None}])


def test_not_null_soft_drops_null_rows():
    v = RuleValidator([{"field": "roe", "check": "not_null", "level": "soft"}])
    records, issues = v.validate([{"roe": 1.0}, {"roe": None}, {"other": 1}])
    assert records == [{"roe": 1.0}]
    assert len(issues) == 1


def test_type_hard_raises_on_wrong_type():
    v = RuleValidator([{"field": "pe", "check": "type", "type": "numeric", "level": "hard"}])
    with pytest.raises(SourceError):
        v.validate([{"pe": "not-a-number"}])


def test_type_soft_drops_wrong_type_but_keeps_none():
    """None 由 not_null/required 负责，type 只校验非空值的类型；None 直通。"""
    v = RuleValidator([{"field": "pe", "check": "type", "value": "numeric", "level": "soft"}])
    records, issues = v.validate([{"pe": 1.0}, {"pe": "xx"}, {"pe": None}])
    assert records == [{"pe": 1.0}, {"pe": None}]
    assert len(issues) == 1


def test_type_str_and_int():
    v = RuleValidator([{"field": "code", "check": "type", "type": "str", "level": "soft"}])
    records, _ = v.validate([{"code": "600519"}, {"code": 1}])
    assert records == [{"code": "600519"}]
    v2 = RuleValidator([{"field": "n", "check": "type", "type": "int", "level": "soft"}])
    records2, _ = v2.validate([{"n": 1}, {"n": 1.5}, {"n": True}])
    assert records2 == [{"n": 1}]  # bool 不算 int


def test_unique_hard_raises_on_duplicate_key():
    v = RuleValidator([{"field": "stock_code", "check": "unique", "level": "hard"}])
    with pytest.raises(SourceError):
        v.validate([{"stock_code": "a"}, {"stock_code": "a"}])


def test_unique_soft_keeps_first_occurrence():
    v = RuleValidator([{"field": "stock_code", "check": "unique", "level": "soft"}])
    records, issues = v.validate(
        [{"stock_code": "a", "v": 1}, {"stock_code": "b", "v": 2}, {"stock_code": "a", "v": 3}]
    )
    assert records == [{"stock_code": "a", "v": 1}, {"stock_code": "b", "v": 2}]
    assert len(issues) == 1


def test_unique_composite_key():
    v = RuleValidator([{"field": ["trading_day", "code"], "check": "unique", "level": "soft"}])
    records, issues = v.validate(
        [
            {"trading_day": "20260828", "code": "a"},
            {"trading_day": "20260828", "code": "b"},
            {"trading_day": "20260828", "code": "a"},
        ]
    )
    assert len(records) == 2
    assert len(issues) == 1


def test_allowed_values_hard_raises_on_disallowed():
    v = RuleValidator([{"field": "term", "check": "allowed_values", "values": ["1Y", "10Y"], "level": "hard"}])
    with pytest.raises(SourceError):
        v.validate([{"term": "1Y"}, {"term": "3Y"}])


def test_allowed_values_soft_drops_disallowed():
    v = RuleValidator([{"field": "term", "check": "allowed_values", "value": ["1Y", "10Y"], "level": "soft"}])
    records, issues = v.validate([{"term": "1Y"}, {"term": "3Y"}, {"term": "10Y"}])
    assert records == [{"term": "1Y"}, {"term": "10Y"}]
    assert len(issues) == 1
