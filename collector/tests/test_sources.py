import pandas as pd
import pytest

import collector.sources.index as index
import collector.sources.industry as industry
import collector.sources.treasury as treasury
import collector.sources.universe as universe


def test_fetch_all_a_valuation_normalizes_columns(monkeypatch):
    raw = pd.DataFrame(
        {
            "代码": ["600519", "000858"],
            "名称": ["贵州茅台", "五粮液"],
            "市盈率-动态": [25.0, 20.0],
            "市净率": [6.0, 4.0],
            "总市值": [2.1e12, 5.0e11],
        }
    )

    class FakeAk:
        @staticmethod
        def stock_zh_a_spot_em():
            return raw

    monkeypatch.setattr(universe.ak, "stock_zh_a_spot_em", FakeAk.stock_zh_a_spot_em)

    df = universe.fetch_all_a_valuation()
    assert list(df.columns) == ["code", "name", "pe", "pb", "market_cap"]
    assert df.iloc[0]["code"] == "600519"
    assert df.iloc[0]["pe"] == pytest.approx(25.0)
    assert df.iloc[0]["market_cap"] == pytest.approx(2.1e12)


def test_fetch_treasury_10y_returns_float(monkeypatch):
    raw = pd.DataFrame({"中国国债收益率10年": [2.21]})

    class FakeAk:
        @staticmethod
        def bond_zh_us_rate():
            return raw

    monkeypatch.setattr(treasury.ak, "bond_zh_us_rate", FakeAk.bond_zh_us_rate)

    assert treasury.fetch_treasury_10y() == pytest.approx(2.21)


def test_fetch_index_valuation_normalizes_columns(monkeypatch):
    raw = pd.DataFrame(
        {
            "trade_date": ["20240801", "20240802"],
            "ts_code": ["000300.SH", "000300.SH"],
            "pe": [12.3, 12.4],
            "pb": [1.5, 1.5],
            "dv_ratio": [2.1, 2.2],
        }
    )

    class FakePro:
        @staticmethod
        def index_dailybasic(ts_code, start_date, end_date):
            assert ts_code == "000300.SH"
            assert start_date == "20240801"
            assert end_date == "20240830"
            return raw

    df = index.fetch_index_valuation(FakePro(), "000300", "20240801", "20240830")
    assert list(df.columns) == ["trading_day", "pe", "pb", "dividend_yield"]
    assert df.iloc[0]["trading_day"] == "20240801"
    assert df.iloc[0]["dividend_yield"] == pytest.approx(2.1)


def test_fetch_shenwan_mapping_normalizes_columns(monkeypatch):
    raw = pd.DataFrame(
        {
            "ts_code": ["600519.SH", "000858.SZ"],
            "name": ["贵州茅台", "五粮液"],
            "industry": ["白酒", "白酒"],
        }
    )

    class FakePro:
        @staticmethod
        def stock_basic(exchange, list_status, fields):
            assert exchange == ""
            assert list_status == "L"
            return raw

    df = industry.fetch_shenwan_mapping(FakePro())
    assert list(df.columns) == ["code", "industry_name"]
    assert df.iloc[0]["code"] == "600519"
    assert df.iloc[1]["code"] == "000858"
    assert df.iloc[0]["industry_name"] == "白酒"
