from collector.calc.snapshot import IndustryValuationCalc


def _records():
    return [
        {
            "industry_code": "801780",
            "industry_name": "银行",
            "pe": 5.6,
            "pb": 0.62,
            "market_cap": 100.0,
            "roe": 11.8,
            "dividend_yield": 5.4,
        },
        {
            "industry_code": "801780",
            "industry_name": "银行",
            "pe": 9.8,
            "pb": 1.05,
            "market_cap": 200.0,
            "roe": 16.4,
            "dividend_yield": 3.9,
        },
        {
            "industry_code": "801120",
            "industry_name": "食品饮料",
            "pe": 22.5,
            "pb": 7.8,
            "market_cap": 300.0,
            "roe": 24.5,
            "dividend_yield": 2.1,
        },
    ]


def test_industry_roe_dividend_weighted_by_market_cap():
    rows = IndustryValuationCalc().compute(_records())
    bank = next(r for r in rows if r["industry_code"] == "801780")
    # roe 加权 = (11.8*100 + 16.4*200) / (100+200) = 14.87
    assert round(bank["roe"], 2) == 14.87
    # dividend 加权 = (5.4*100 + 3.9*200) / 300 = 4.40
    assert round(bank["dividend_yield"], 2) == 4.40


def test_industry_roe_null_when_all_missing():
    rows = IndustryValuationCalc().compute(
        [
            {
                "industry_code": "x",
                "industry_name": "X",
                "pe": 10.0,
                "pb": 1.0,
                "market_cap": 100.0,
                "roe": None,
                "dividend_yield": None,
            },
        ]
    )
    assert rows[0]["roe"] is None
    assert rows[0]["dividend_yield"] is None
