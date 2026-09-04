import datetime as dt

import pandas as pd

from collector.sources.plugins import IndexValuationSource, IndustryUniverseSource, ShenwanMappingSource


def test_shenwan_mapping_loops_31(mocker):
    pro = mocker.Mock()
    pro.index_classify.return_value = pd.DataFrame({"index_code": ["801010.SI", "801030.SI"]})
    pro.index_member_all.side_effect = [
        pd.DataFrame(
            {"ts_code": ["000001.SZ"], "name": ["平安银行"], "l1_code": ["801010.SI"], "l1_name": ["农林牧渔"]}
        ),
        pd.DataFrame(
            {"ts_code": ["600519.SH"], "name": ["贵州茅台"], "l1_code": ["801030.SI"], "l1_name": ["食品饮料"]}
        ),
    ]
    src = ShenwanMappingSource("sw", pro_factory=lambda: pro)
    df = src.fetch({})
    assert list(df.columns) == ["code", "stock_name", "industry_code", "industry_name"]
    assert df.iloc[0]["code"] == "000001"
    assert df.iloc[0]["stock_name"] == "平安银行"
    assert df.iloc[0]["industry_code"] == "801010"


def test_index_valuation_merges_dividend(mocker):
    pro = mocker.Mock()
    pro.index_dailybasic.return_value = pd.DataFrame({"trade_date": ["20260828"], "pe": [12.0], "pb": [1.4]})

    def dividend_fetch(index_code, start, end):
        return {"000300": 2.35}

    src = IndexValuationSource(
        "idx", pro_factory=lambda: pro, dividend_fetch=dividend_fetch, index_codes={"000300": "沪深300"}
    )
    df = src.fetch({"start": "20260828", "end": "20260828"})
    assert df.iloc[0]["dividend_yield"] == 2.35


def test_index_valuation_defaults_range_to_today(mocker):
    pro = mocker.Mock()
    pro.index_dailybasic.return_value = pd.DataFrame({"trade_date": ["20260829"], "pe": [12.0], "pb": [1.4]})
    src = IndexValuationSource("idx", pro_factory=lambda: pro, index_codes={"000300": "沪深300"})
    src.fetch({})
    expected = dt.date.today().strftime("%Y%m%d")
    pro.index_dailybasic.assert_called_once_with(ts_code="000300.SH", start_date=expected, end_date=expected)


def test_index_valuation_defaults_range_to_date_param(mocker):
    pro = mocker.Mock()
    pro.index_dailybasic.return_value = pd.DataFrame({"trade_date": ["20260828"], "pe": [12.0], "pb": [1.4]})
    src = IndexValuationSource("idx", pro_factory=lambda: pro, index_codes={"000300": "沪深300"})
    src.fetch({"date": "2026-08-28"})
    pro.index_dailybasic.assert_called_once_with(ts_code="000300.SH", start_date="20260828", end_date="20260828")


def _industry_pro():
    """IndustryUniverseSource 用的 tushare FakePro：daily_basic 全市场估值 + stock_basic 股票名。"""

    class FakePro:
        def daily_basic(self, trade_date=None):
            return pd.DataFrame(
                {
                    "ts_code": ["000001.SZ", "600519.SH"],
                    "pe_ttm": [10.0, 30.0],
                    "pb": [1.2, 8.0],
                    "total_mv": [2.0e7, 2.0e8],  # 万元
                }
            )

        def stock_basic(self, list_status=None, fields=None):
            return pd.DataFrame({"ts_code": ["000001.SZ", "600519.SH"], "name": ["平安银行", "贵州茅台"]})

    return FakePro()


def test_industry_universe_joins_mapping(mocker):
    cursor = mocker.MagicMock()
    cursor.fetchall.side_effect = [
        [
            ("000001", "801010", "农林牧渔"),
            ("600519", "801030", "食品饮料"),
        ],
        [
            ("000001", 11.8),
            ("600519", 24.5),
        ],
        [
            ("000001", 5.4),
            ("600519", 2.1),
        ],
    ]
    cursor.__enter__.return_value = cursor
    conn = mocker.MagicMock()
    conn.cursor.return_value = cursor
    conn.__enter__.return_value = conn

    def fake_conn_factory():
        return conn

    src = IndustryUniverseSource(
        "industry_universe", conn_factory=fake_conn_factory, pro_factory=lambda: _industry_pro()
    )
    df = src.fetch({})
    assert "industry_code" in df.columns
    assert "industry_name" in df.columns
    assert df.loc[df["代码"] == "000001", "industry_code"].iloc[0] == "801010"
    assert df.loc[df["代码"] == "600519", "industry_name"].iloc[0] == "食品饮料"
    assert df.loc[df["代码"] == "000001", "roe"].iloc[0] == 11.8
    assert df.loc[df["代码"] == "000001", "dividend_yield"].iloc[0] == 5.4


# ---------------------------------------------------------------- C-P1-4 日期归一化

import pytest

from collector.sources.plugins import normalize_date


def test_normalize_date_iso_and_compact():
    assert normalize_date("2026-08-28") == "20260828"
    assert normalize_date("20260828") == "20260828"  # 已规范直通
    assert normalize_date(dt.date(2026, 8, 28)) == "20260828"


def test_normalize_date_invalid_raises():
    with pytest.raises(ValueError, match="非法日期参数"):
        normalize_date("2026/08/28", "start")
    with pytest.raises(ValueError, match="非法日期参数"):
        normalize_date("20261340")  # 不存在的月份/日


def test_index_valuation_normalizes_iso_range(mocker):
    pro = mocker.Mock()
    pro.index_dailybasic.return_value = pd.DataFrame({"trade_date": ["20260828"], "pe": [12.0], "pb": [1.4]})
    src = IndexValuationSource("idx", pro_factory=lambda: pro, index_codes={"000300": "沪深300"})
    src.fetch({"start": "2026-08-01", "end": "2026-08-28"})
    pro.index_dailybasic.assert_called_once_with(ts_code="000300.SH", start_date="20260801", end_date="20260828")


# ---------------------------------------------------------------- L4 上游表为空（冷启动缝隙）


def _empty_mapping_conn_factory(mocker):
    cursor = mocker.MagicMock()
    cursor.fetchall.return_value = []
    cursor.__enter__.return_value = cursor
    conn = mocker.MagicMock()
    conn.cursor.return_value = cursor
    conn.__enter__.return_value = conn
    return lambda: conn


def test_industry_universe_empty_upstream_table_yields_zero_rows(mocker):
    """shenwan_industry_mapping 为空（冷启动缝隙）时 inner join 产出 0 行，而非报错或全量直通。"""
    src = IndustryUniverseSource(
        "industry_universe",
        conn_factory=_empty_mapping_conn_factory(mocker),
        pro_factory=lambda: _industry_pro(),
    )
    df = src.fetch({})
    assert len(df) == 0
    assert "industry_code" in df.columns


def test_industry_universe_empty_result_fails_min_rows_hard(mocker):
    """上游空表产出的 0 行结果经 min_rows hard 校验必须判失败——冷启动缝隙（C-P1-4）的回归保障。"""
    from collector.sources.base import SourceError
    from collector.validators.rules import RuleValidator

    src = IndustryUniverseSource(
        "industry_universe",
        conn_factory=_empty_mapping_conn_factory(mocker),
        pro_factory=lambda: _industry_pro(),
    )
    records = src.fetch({}).to_dict("records")
    assert records == []
    validator = RuleValidator([{"check": "min_rows", "value": 1, "level": "hard"}])
    with pytest.raises(SourceError, match="行数 0 < 1"):
        validator.validate(records)


# ---------------------------------------------------------------- C-3.2 指数股息率 dividend_fetch


def test_make_index_dividend_fetch_returns_weighted_non_none(mocker):
    """真实股息率拉取：成分股权重 × dv_ttm 加权均值，返回非 None。"""
    from collector.sources.plugins import make_index_dividend_fetch

    pro = mocker.Mock()
    pro.trade_cal.return_value = pd.DataFrame({"cal_date": ["20260828"], "is_open": [1]})
    pro.index_weight.return_value = pd.DataFrame({"con_code": ["600519.SH", "000001.SZ"], "weight": [60.0, 40.0]})
    pro.daily_basic.return_value = pd.DataFrame({"ts_code": ["600519.SH", "000001.SZ"], "dv_ttm": [2.0, 3.0]})
    fetch = make_index_dividend_fetch(pro_factory=lambda: pro)
    value = fetch("000300", "20260801", "20260828")
    assert value is not None
    assert value == 2.4  # (60*2 + 40*3) / 100


def test_make_index_dividend_fetch_falls_back_default_when_no_data(mocker):
    """积分不足/无数据时不崩，回退默认值（非 None）。"""
    from collector.sources.plugins import make_index_dividend_fetch

    pro = mocker.Mock()
    pro.trade_cal.return_value = pd.DataFrame({"cal_date": ["20260828"], "is_open": [1]})
    pro.index_weight.return_value = pd.DataFrame(columns=["con_code", "weight"])
    pro.daily_basic.return_value = pd.DataFrame(columns=["ts_code", "dv_ttm"])
    fetch = make_index_dividend_fetch(pro_factory=lambda: pro, default=0.0)
    assert fetch("000300", "20260801", "20260828") == 0.0


def test_make_index_dividend_fetch_swallows_api_exception(mocker):
    """tushare 抛错（积分/限流）时也应返回默认值，不向外崩。"""
    from collector.sources.plugins import make_index_dividend_fetch

    def boom_pro():
        raise RuntimeError("tushare 403")

    fetch = make_index_dividend_fetch(pro_factory=boom_pro, default=0.0)
    assert fetch("000300", "20260801", "20260828") == 0.0
