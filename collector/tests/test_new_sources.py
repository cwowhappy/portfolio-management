import pandas as pd

from collector.sources.plugins import IndexConstituentSource, TreasuryCurveSource


def _curve_df(dates, **overrides):
    """构造 bond_china_yield 形状的 mock：全期限列 + 一行非国债曲线（应被过滤）。"""
    n = len(dates)
    data = {
        "曲线名称": ["中债国债收益率曲线"] * n,
        "日期": dates,
        "1年": [1.5] * n,
        "3年": [1.7] * n,
        "5年": [1.9] * n,
        "10年": [2.2] * n,
        "30年": [2.5] * n,
    }
    for col, values in overrides.items():
        data[col] = values
    # 混入一行其他曲线（如国开债），验证只保留中债国债收益率曲线
    other = {**{k: v[:1] for k, v in data.items()}, "曲线名称": ["中债国开债收益率曲线"]}
    return pd.concat([pd.DataFrame(data), pd.DataFrame(other)], ignore_index=True)


def test_treasury_curve_multiterm(mocker):
    import collector.sources.plugins as p

    mocker.patch.object(p.ak, "bond_china_yield", return_value=_curve_df(["2026-08-28"]))
    src = TreasuryCurveSource("curve")
    df = src.fetch({"start": "2026-08-28", "end": "2026-08-28"})
    assert set(df["term"]) == {"1Y", "3Y", "5Y", "10Y", "30Y"}
    assert len(df) == 5  # 非国债曲线行已被过滤，单日 5 个期限


def test_treasury_curve_skips_na_term(mocker):
    import collector.sources.plugins as p

    mocker.patch.object(p.ak, "bond_china_yield", return_value=_curve_df(["2026-08-28"], **{"30年": [None]}))
    src = TreasuryCurveSource("curve")
    df = src.fetch({"start": "2026-08-28", "end": "2026-08-28"})
    assert set(df["term"]) == {"1Y", "3Y", "5Y", "10Y"}  # 30 年为 NaN 的行不产出 30Y


def test_treasury_curve_missing_term_column_fails_loudly(mocker):
    """期限列缺失时显式报错，不再静默跳过（1Y/3Y 静默缺失的教训）。"""
    import pytest

    import collector.sources.plugins as p
    from collector.sources.base import SourceError

    mocker.patch.object(
        p.ak,
        "bond_china_yield",
        return_value=_curve_df(["2026-08-28"]).drop(columns=["1年", "3年"]),
    )
    src = TreasuryCurveSource("curve")
    with pytest.raises(SourceError, match="缺少期限列"):
        src.fetch({})


def test_index_constituent(mocker):
    pro = mocker.Mock()
    pro.index_weight.return_value = pd.DataFrame(
        {"index_code": ["000300.SH"], "con_code": ["600519.SH"], "weight": [5.0]}
    )
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

    mocker.patch.object(p.ak, "bond_china_yield", return_value=_curve_df(["2026-08-27", "2026-08-28"]))
    src = TreasuryCurveSource("curve", conn_factory=_conn_factory(mocker, dt.date(2026, 8, 27)))
    df = src.fetch({})
    assert set(df["trading_day"]) == {dt.date(2026, 8, 28)}
    assert df.iloc[0]["trading_day"] == dt.date(2026, 8, 28)  # Timestamp 显式转 date


def test_treasury_curve_full_load_when_table_empty(mocker):
    import collector.sources.plugins as p

    mocker.patch.object(
        p.ak,
        "bond_china_yield",
        return_value=_curve_df([pd.Timestamp("2026-08-27"), pd.Timestamp("2026-08-28")]),
    )
    src = TreasuryCurveSource("curve", conn_factory=_conn_factory(mocker, None))
    df = src.fetch({})
    assert set(df["trading_day"]) == {dt.date(2026, 8, 27), dt.date(2026, 8, 28)}


# ---------------------------------------------------------------- C-2 TreasuryCurveSource 区间回填


def test_treasury_curve_backfill_respects_range_on_empty_table(mocker):
    """空表 + 显式 start/end 回填：只返回区间内行，不再整段写历史。"""
    import collector.sources.plugins as p

    mocker.patch.object(p.ak, "bond_china_yield", return_value=_curve_df(["2026-07-30", "2026-08-05", "2026-08-12"]))
    src = TreasuryCurveSource("curve", conn_factory=_conn_factory(mocker, None))
    df = src.fetch({"start": "2026-08-01", "end": "2026-08-10"})
    assert set(df["trading_day"]) == {dt.date(2026, 8, 5)}


def test_treasury_curve_backfill_range_overrides_watermark(mocker):
    """显式区间回填时以区间为准：即使 DB watermark 已越过区间起点，区间内历史仍被返回，
    区间外行（即使晚于 watermark）不被写入。"""
    import collector.sources.plugins as p

    mocker.patch.object(p.ak, "bond_china_yield", return_value=_curve_df(["2026-08-05", "2026-09-02"]))
    src = TreasuryCurveSource("curve", conn_factory=_conn_factory(mocker, dt.date(2026, 9, 1)))
    df = src.fetch({"start": "2026-08-01", "end": "2026-08-10"})
    assert set(df["trading_day"]) == {dt.date(2026, 8, 5)}


def test_treasury_curve_incremental_still_uses_watermark_when_no_range(mocker):
    """无显式区间（增量调度）仍保留 DB watermark 行为，不受区间逻辑影响。"""
    import collector.sources.plugins as p

    mocker.patch.object(p.ak, "bond_china_yield", return_value=_curve_df(["2026-08-27", "2026-08-28", "2026-09-02"]))
    src = TreasuryCurveSource("curve", conn_factory=_conn_factory(mocker, dt.date(2026, 8, 27)))
    df = src.fetch({})
    assert set(df["trading_day"]) == {dt.date(2026, 8, 28), dt.date(2026, 9, 2)}


def test_treasury_curve_long_range_fetches_in_chunks(mocker):
    """bond_china_yield 单次区间上限约 1 年，长区间按 180 天切块分多次调用。"""
    import collector.sources.plugins as p

    mocked = mocker.patch.object(p.ak, "bond_china_yield", return_value=_curve_df(["2026-08-05"]))
    src = TreasuryCurveSource("curve", conn_factory=_conn_factory(mocker, None))
    src.fetch({"start": "2026-01-01", "end": "2026-08-10"})  # 222 天 > 180 天
    assert mocked.call_count == 2
