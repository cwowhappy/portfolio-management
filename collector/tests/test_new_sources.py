import pandas as pd

from collector.sources.plugins import TreasuryCurveSource, IndexConstituentSource


def test_treasury_curve_multiterm(mocker):
    import collector.sources.plugins as p
    mocker.patch.object(p.ak, "bond_zh_us_rate", return_value=pd.DataFrame(
        {"日期": ["2026-08-28"], "中国国债收益率1年": [1.8], "中国国债收益率10年": [2.2]}))
    src = TreasuryCurveSource("curve")
    df = src.fetch({})
    assert set(df["term"]) == {"1Y", "10Y"}


def test_index_constituent(mocker):
    pro = mocker.Mock()
    pro.index_weight.return_value = pd.DataFrame(
        {"index_code": ["000300.SH"], "con_code": ["600519.SH"], "weight": [5.0]})
    src = IndexConstituentSource("ic", pro_factory=lambda: pro, index_codes={"000300": "沪深300"})
    df = src.fetch({})
    assert df.iloc[0]["stock_code"] == "600519"
    assert df.iloc[0]["weight"] == 5.0
