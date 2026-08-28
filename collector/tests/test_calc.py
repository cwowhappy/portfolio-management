from collector.calc.snapshot import SnapshotCalc, IndustryValuationCalc


def test_snapshot_median_excludes_outliers():
    records = [
        {"pe": 10.0, "pb": 1.5},
        {"pe": 20.0, "pb": 0.8},
        {"pe": -5.0, "pb": 2.0},
        {"pe": 200.0, "pb": 3.0},
    ]
    out = SnapshotCalc().compute(records)
    assert out == [{"pe_median": 15.0, "pb_median": 1.75, "net_breaker_count": 1, "net_breaker_ratio": 0.25}]


def test_industry_weighted():
    records = [
        {"code": "a", "industry_code": "801780", "industry_name": "银行", "pe": 10.0, "pb": 1.0, "market_cap": 100.0},
        {"code": "b", "industry_code": "801780", "industry_name": "银行", "pe": 20.0, "pb": 2.0, "market_cap": 200.0},
    ]
    rows = IndustryValuationCalc().compute(records)
    bank = rows[0]
    assert bank["industry_code"] == "801780"
    assert round(bank["pe"], 2) == 16.67
    assert round(bank["pb"], 2) == 1.67
