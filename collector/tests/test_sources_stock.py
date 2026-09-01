import pandas as pd
import pytest

import collector.sources.plugins as plugins


def _fake_stock_basic():
    return pd.DataFrame(
        {
            "ts_code": ["600519.SH", "000858.SZ", "830000.BJ", "600001.SH"],
            "name": ["贵州茅台", "五粮液", "某北交所", "ST某某"],
        }
    )


def _fake_daily_basic():
    return pd.DataFrame(
        {
            "ts_code": ["600519.SH", "000858.SZ", "830000.BJ", "600001.SH"],
            "pe_ttm": [22.5, 18.2, 10.0, 5.0],
            "pb": [7.8, 4.5, 1.0, 0.5],
            "dv_ttm": [2.1, 2.8, 0.5, 0.0],
            "total_mv": [210000.0, 58000.0, 1000.0, 500.0],  # 万元
            "circ_mv": [210000.0, 58000.0, 1000.0, 500.0],
            "turnover_rate": [0.35, 0.62, 0.10, 0.05],
        }
    )


class _FakePro:
    def stock_basic(self, list_status=None, fields=None):
        return _fake_stock_basic()

    def daily_basic(self, trade_date=None):
        return _fake_daily_basic()


def test_stock_valuation_daily_filters_st_and_bse(monkeypatch):
    src = plugins.StockValuationDailySource("svd", pro_factory=lambda: _FakePro())
    df = src.fetch({"date": "2026-08-27"})

    codes = set(df["stock_code"])
    assert codes == {"600519", "000858"}  # 剔除北交所 830000 与 ST 600001
    assert list(df.columns) == [
        "stock_code",
        "stock_name",
        "pe_ttm",
        "pb",
        "dividend_yield",
        "total_mv",
        "circ_mv",
        "turnover_rate",
    ]
    # total_mv 万元 → 元：210000.0 万元 * 10000 = 2.1e9 元
    row = df[df["stock_code"] == "600519"].iloc[0]
    assert row["total_mv"] == pytest.approx(2100000000.0)
    assert row["stock_name"] == "贵州茅台"


def test_stock_financial_normalizes_and_backfills(monkeypatch):
    # 用 monkeypatch 替换 _last_n_periods，隔离真实的 12 期逻辑
    periods = ["20260630", "20260331"]
    monkeypatch.setattr(plugins, "_last_n_periods", lambda n: periods)

    def fake_fina(period):
        return pd.DataFrame(
            {
                "ts_code": ["600519.SH", "830000.BJ"],
                "end_date": [period, period],
                "roe": [24.5, 10.0],
                "roa": [18.2, 5.0],
                "grossprofit_margin": [91.2, 20.0],
                "debt_to_assets": [21.3, 40.0],
                "current_ratio": [3.8, 1.5],
                "or_yoy": [16.8, 3.0],
                "netprofit_yoy": [15.2, 2.0],
            }
        )

    class FakePro:
        def fina_indicator(self, period=None):
            return fake_fina(period)

    src = plugins.StockFinancialSource("sf", pro_factory=lambda: FakePro())
    df = src.fetch({})

    assert set(df.columns) == {
        "report_date",
        "stock_code",
        "roe",
        "roa",
        "gross_margin",
        "debt_to_assets",
        "current_ratio",
        "revenue_yoy",
        "netprofit_yoy",
    }
    assert set(df["stock_code"]) == {"600519"}  # 剔除北交所 830000
    assert list(df["report_date"].unique()) == periods
    assert df.iloc[0]["gross_margin"] == pytest.approx(91.2)
