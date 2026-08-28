import pandas as pd

from collector.sources.plugins import IndustryUniverseSource, ShenwanMappingSource, IndexValuationSource


def test_shenwan_mapping_loops_31(mocker):
    pro = mocker.Mock()
    pro.index_classify.return_value = pd.DataFrame({"index_code": ["801010.SI", "801030.SI"]})
    pro.index_member_all.side_effect = [
        pd.DataFrame({"ts_code": ["000001.SZ"], "l1_code": ["801010.SI"], "l1_name": ["农林牧渔"]}),
        pd.DataFrame({"ts_code": ["600519.SH"], "l1_code": ["801030.SI"], "l1_name": ["食品饮料"]}),
    ]
    src = ShenwanMappingSource("sw", pro_factory=lambda: pro)
    df = src.fetch({})
    assert list(df.columns) == ["code", "industry_code", "industry_name"]
    assert df.iloc[0]["code"] == "000001"
    assert df.iloc[0]["industry_code"] == "801010"


def test_index_valuation_merges_dividend(mocker):
    pro = mocker.Mock()
    pro.index_dailybasic.return_value = pd.DataFrame(
        {"trade_date": ["20260828"], "pe": [12.0], "pb": [1.4]}
    )
    def dividend_fetch(index_code, start, end):
        return {"000300": 2.35}
    src = IndexValuationSource("idx", pro_factory=lambda: pro, dividend_fetch=dividend_fetch,
                               index_codes={"000300": "沪深300"})
    df = src.fetch({"start": "20260828", "end": "20260828"})
    assert df.iloc[0]["dividend_yield"] == 2.35


def test_industry_universe_joins_mapping(mocker):
    universe = pd.DataFrame({
        "代码": ["000001", "600519"],
        "名称": ["平安银行", "贵州茅台"],
        "市盈率-动态": [10.0, 30.0],
        "市净率": [1.2, 8.0],
        "总市值": [2.0e11, 2.0e12],
    })
    mocker.patch("akshare.stock_zh_a_spot_em", return_value=universe)

    cursor = mocker.MagicMock()
    cursor.fetchall.return_value = [
        ("000001", "801010", "农林牧渔"),
        ("600519", "801030", "食品饮料"),
    ]
    cursor.__enter__.return_value = cursor
    conn = mocker.MagicMock()
    conn.cursor.return_value = cursor
    conn.__enter__.return_value = conn

    def fake_conn_factory():
        return conn

    src = IndustryUniverseSource("industry_universe", conn_factory=fake_conn_factory)
    df = src.fetch({})
    assert "industry_code" in df.columns
    assert "industry_name" in df.columns
    assert df.loc[df["代码"] == "000001", "industry_code"].iloc[0] == "801010"
    assert df.loc[df["代码"] == "600519", "industry_name"].iloc[0] == "食品饮料"
