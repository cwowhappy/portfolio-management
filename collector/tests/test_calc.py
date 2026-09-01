from collector.calc.snapshot import IndustryValuationCalc, SnapshotCalc


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


# ---------------------------------------------------------------- 脏记录跳过


def test_industry_weighted_skips_dirty_records():
    """pe 缺失/pe≤0/缺 market_cap 的脏记录不参与行业加权，结果只按干净记录计算。"""
    records = [
        {"industry_code": "801780", "industry_name": "银行", "pe": 10.0, "pb": 1.0, "market_cap": 100.0},
        {"industry_code": "801780", "industry_name": "银行", "pe": -3.0, "pb": 9.0, "market_cap": 900.0},
        {"industry_code": "801780", "industry_name": "银行", "pe": 0.0, "pb": 9.0, "market_cap": 900.0},
        {"industry_code": "801780", "industry_name": "银行", "pe": None, "pb": 9.0, "market_cap": 900.0},
        {"industry_code": "801780", "industry_name": "银行", "pe": 50.0, "pb": 9.0, "market_cap": None},
        {"industry_code": "801780", "industry_name": "银行", "pe": 50.0, "pb": 9.0},  # 无 market_cap 键
    ]
    rows = IndustryValuationCalc().compute(records)
    assert rows == [
        {
            "industry_code": "801780",
            "industry_name": "银行",
            "pe": 10.0,
            "pb": 1.0,
            "roe": None,
            "dividend_yield": None,
        }
    ]


def test_industry_weighted_drops_non_positive_cap_group():
    """组内总市值 ≤0（如 0 市值脏记录）整组剔除，不产出除零结果。"""
    records = [
        {"industry_code": "801790", "industry_name": "非银金融", "pe": 10.0, "pb": 1.0, "market_cap": 0.0},
    ]
    assert IndustryValuationCalc().compute(records) == []
