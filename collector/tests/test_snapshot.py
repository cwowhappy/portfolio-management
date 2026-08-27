import pandas as pd
from collector.calc.snapshot import compute_snapshot, compute_industry_valuation


def _universe():
    return pd.DataFrame({
        "code": ["a", "b", "c", "d"],
        "name": ["A", "B", "C", "D"],
        "pe": [10.0, 20.0, -5.0, 200.0],   # -5 剔除(亏损), 200 剔除(>100)
        "pb": [1.5, 0.8, 2.0, 3.0],        # 0.8 破净
        "market_cap": [100.0, 200.0, 300.0, 400.0],
    })


def test_compute_snapshot_median_excludes_outliers():
    s = compute_snapshot(_universe())
    # 有效 PE: 10, 20 → 中位数 15
    assert s["pe_median"] == 15.0
    assert s["net_breaker_count"] == 1
    assert s["net_breaker_ratio"] == 0.25


def test_compute_industry_valuation_weighted():
    u = _universe()
    m = pd.DataFrame({
        "code": ["a", "b", "c", "d"],
        "industry_code": ["801780", "801780", "801990", "801990"],
        "industry_name": ["银行", "银行", "电子", "电子"],
    })
    rows = compute_industry_valuation(u, m)
    bank = next(r for r in rows if r["industry_code"] == "801780")
    # 银行市值加权 PE = (10*100 + 20*200) / (100+200) = 16.67
    assert round(bank["pe"], 2) == 16.67
    assert bank["industry_name"] == "银行"


def test_compute_industry_valuation_skips_all_nan_pb():
    u = pd.DataFrame({
        "code": ["a", "b"],
        "name": ["A", "B"],
        "pe": [10.0, 20.0],
        "pb": [float("nan"), float("nan")],
        "market_cap": [100.0, 200.0],
    })
    m = pd.DataFrame({
        "code": ["a", "b"],
        "industry_code": ["801780", "801780"],
        "industry_name": ["银行", "银行"],
    })
    assert compute_industry_valuation(u, m) == []


def test_compute_snapshot_median_none_when_no_valid():
    u = pd.DataFrame({
        "code": ["a"],
        "name": ["A"],
        "pe": [float("nan")],
        "pb": [float("nan")],
        "market_cap": [1.0],
    })
    s = compute_snapshot(u)
    assert s["pe_median"] is None
    assert s["pb_median"] is None
    assert s["net_breaker_count"] == 0
    assert s["net_breaker_ratio"] == 0.0
