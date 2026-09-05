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


def _financial_stock_basic():
    """StockFinancialSource 用的全 A 股票池：含健康/ST/退市/北交所，供 ST 过滤断言。"""
    return pd.DataFrame(
        {
            "ts_code": ["600519.SH", "000858.SZ", "600001.SH", "600002.SH", "830000.BJ"],
            "name": ["贵州茅台", "五粮液", "ST某某", "退市股", "某北交所"],
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
    monkeypatch.setattr(plugins, "_last_n_periods", lambda n: ["20260331", "20260630"])

    class FakePro:
        def fina_indicator(self, ts_code=None):
            assert ts_code is not None
            return _financial_stock_frame(ts_code)

        def stock_basic(self, list_status=None, fields=None):
            return _financial_stock_basic()

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
    assert set(df["stock_code"]) == {"600519", "000858"}  # 剔除 ST/退市/北交所
    assert set(df["report_date"].unique()) == {"20260331", "20260630"}
    assert df.iloc[0]["gross_margin"] == pytest.approx(91.2)


def test_stock_financial_all_empty_returns_empty_frame(monkeypatch):
    # 所有股票都无数据（None）→ 触发空帧守卫：逐股跳过 + 返回空列 DataFrame
    monkeypatch.setattr(plugins, "_last_n_periods", lambda n: ["20260331", "20260630"])

    fetched = []

    class FakePro:
        def fina_indicator(self, ts_code=None):
            fetched.append(ts_code)
            return None

        def stock_basic(self, list_status=None, fields=None):
            return _financial_stock_basic()

    src = plugins.StockFinancialSource("sf", pro_factory=lambda: FakePro())
    df = src.fetch({})

    assert df.empty
    assert list(df.columns) == [
        "report_date",
        "stock_code",
        "roe",
        "roa",
        "gross_margin",
        "debt_to_assets",
        "current_ratio",
        "revenue_yoy",
        "netprofit_yoy",
    ]
    assert sorted(fetched) == ["000858.SZ", "600519.SH"]  # 每个有效股都请求过


def test_stock_financial_mixed_empty_concats_valid_frames(monkeypatch):
    # 部分股有数据、部分股为 None/空 → 仅拼接有效帧，列/重命名/口径过滤保持一致
    monkeypatch.setattr(plugins, "_last_n_periods", lambda n: ["20260331", "20260630"])

    class FakePro:
        def fina_indicator(self, ts_code=None):
            if ts_code == "000858.SZ":
                return None
            if ts_code == "600003.SH":
                return pd.DataFrame()
            return _financial_stock_frame(ts_code)

        def stock_basic(self, list_status=None, fields=None):
            return pd.DataFrame(
                {
                    "ts_code": ["600519.SH", "000858.SZ", "600003.SH"],
                    "name": ["贵州茅台", "五粮液", "健康股"],
                }
            )

    src = plugins.StockFinancialSource("sf", pro_factory=lambda: FakePro())
    df = src.fetch({})

    assert list(df.columns) == [
        "report_date",
        "stock_code",
        "roe",
        "roa",
        "gross_margin",
        "debt_to_assets",
        "current_ratio",
        "revenue_yoy",
        "netprofit_yoy",
    ]
    assert set(df["report_date"].unique()) == {"20260331", "20260630"}
    assert set(df["stock_code"]) == {"600519"}  # None/空股被跳过


def _financial_stock_frame(ts_code, periods=("20260331", "20260630")):
    """单只股票的 fina_indicator 返回：多报告期（end_date）的财务指标行。"""
    return pd.DataFrame(
        {
            "ts_code": [ts_code] * len(periods),
            "end_date": list(periods),
            "roe": [24.5] * len(periods),
            "roa": [18.2] * len(periods),
            "grossprofit_margin": [91.2] * len(periods),
            "debt_to_assets": [21.3] * len(periods),
            "current_ratio": [3.8] * len(periods),
            "or_yoy": [16.8] * len(periods),
            "netprofit_yoy": [15.2] * len(periods),
        }
    )


def test_stock_financial_filters_st_retired_and_bse(monkeypatch):
    """对齐 StockValuationDailySource P1 口径：仅沪深正常交易股，剔除 ST/退市/北交所。"""
    monkeypatch.setattr(plugins, "_last_n_periods", lambda n: ["20260331", "20260630"])

    fetched = []

    class FakePro:
        def fina_indicator(self, ts_code=None):
            fetched.append(ts_code)
            return _financial_stock_frame(ts_code)

        def stock_basic(self, list_status=None, fields=None):
            return _financial_stock_basic()  # 含 ST/退市/北交所

    src = plugins.StockFinancialSource("sf", pro_factory=lambda: FakePro())
    df = src.fetch({})

    assert set(df["stock_code"]) == {"600519", "000858"}  # 剔除 ST 600001、退市 600002、北交所 830000
    assert sorted(fetched) == ["000858.SZ", "600519.SH"]  # 未请求 ST/退市/北交所


def test_stock_financial_applies_rate_limiter_before_each_stock(monkeypatch):
    """FR-11/C-5：每只个股请求上游前必须经 RateLimiter.wait() 限速。"""
    monkeypatch.setattr(plugins, "_last_n_periods", lambda n: ["20260331", "20260630"])

    class FakePro:
        def fina_indicator(self, ts_code=None):
            return _financial_stock_frame(ts_code)

        def stock_basic(self, list_status=None, fields=None):
            return _financial_stock_basic()

    waits = []

    class _FakeLimiter:
        def wait(self):
            waits.append(1)

    src = plugins.StockFinancialSource("sf", pro_factory=lambda: FakePro(), limiter=_FakeLimiter())
    src.fetch({})

    assert len(waits) == 2  # 2 个有效股，每个都先限速再请求


def test_last_n_periods_quarter_ends(monkeypatch):
    # 冻结今天（2026-09-01）：dt.date 不可变，替换 plugins.dt 为 today() 固定的 date 子类
    import datetime as _real_dt
    import types

    class _FrozenDate(_real_dt.date):
        @classmethod
        def today(cls):
            return cls(2026, 9, 1)

    monkeypatch.setattr(plugins, "dt", types.SimpleNamespace(date=_FrozenDate, timedelta=_real_dt.timedelta))

    assert plugins._last_n_periods(1) == ["20260630"]  # 最近已结束季度
    periods = plugins._last_n_periods(12)
    assert periods == [
        "20230930",
        "20231231",
        "20240331",
        "20240630",
        "20240930",
        "20241231",
        "20250331",
        "20250630",
        "20250930",
        "20251231",
        "20260331",
        "20260630",
    ]
    assert len(periods) == 12
    assert periods == sorted(periods)
