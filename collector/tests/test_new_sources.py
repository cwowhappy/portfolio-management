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


# ---------------------------------------------------------------- TreasuryCurveSource 增量拉取

import datetime as dt


def _conn_factory(mocker, max_day):
    cursor = mocker.MagicMock()
    cursor.fetchone.return_value = (max_day,)
    cursor.__enter__.return_value = cursor
    conn = mocker.MagicMock()
    conn.cursor.return_value = cursor
    conn.__enter__.return_value = conn
    return lambda: conn


def test_treasury_curve_incremental_skips_loaded_days(mocker):
    import collector.sources.plugins as p
    mocker.patch.object(p.ak, "bond_zh_us_rate", return_value=pd.DataFrame(
        {"日期": ["2026-08-27", "2026-08-28"],
         "中国国债收益率1年": [1.7, 1.8]}))
    src = TreasuryCurveSource("curve", conn_factory=_conn_factory(mocker, dt.date(2026, 8, 27)))
    df = src.fetch({})
    assert set(df["trading_day"]) == {dt.date(2026, 8, 28)}
    assert df.iloc[0]["trading_day"] == dt.date(2026, 8, 28)  # Timestamp 显式转 date


def test_treasury_curve_full_load_when_table_empty(mocker):
    import collector.sources.plugins as p
    mocker.patch.object(p.ak, "bond_zh_us_rate", return_value=pd.DataFrame(
        {"日期": [pd.Timestamp("2026-08-27"), pd.Timestamp("2026-08-28")],
         "中国国债收益率1年": [1.7, 1.8]}))
    src = TreasuryCurveSource("curve", conn_factory=_conn_factory(mocker, None))
    df = src.fetch({})
    assert set(df["trading_day"]) == {dt.date(2026, 8, 27), dt.date(2026, 8, 28)}
