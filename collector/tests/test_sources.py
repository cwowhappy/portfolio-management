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


def test_fetch_treasury_10y_returns_latest_by_date(monkeypatch):
    # 最新日期放最后，验证函数按日期显式降序取最新值（不依赖 akshare 隐式顺序）
    raw = pd.DataFrame(
        {
            "date": ["2024-08-01", "2024-08-02"],
            "中国国债收益率10年": [2.21, 2.25],
        }
    )

    class FakeAk:
        @staticmethod
        def bond_zh_us_rate():
            return raw

    monkeypatch.setattr(treasury.ak, "bond_zh_us_rate", FakeAk.bond_zh_us_rate)

    assert treasury.fetch_treasury_10y() == pytest.approx(2.25)


def test_fetch_index_valuation_normalizes_columns(monkeypatch):
    raw = pd.DataFrame(
        {
            "trade_date": ["20240801", "20240802"],
            "ts_code": ["000300.SH", "000300.SH"],
            "pe": [12.3, 12.4],
            "pb": [1.5, 1.5],
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
    assert df.iloc[0]["dividend_yield"] is None


def test_fetch_shenwan_mapping_uses_sw2021_members():
    industries = pd.DataFrame(
        {
            "index_code": ["801010.SI", "801030.SI"],
            "industry_name": ["农林牧渔", "基础化工"],
        }
    )
    members = {
        "801010.SI": pd.DataFrame(
            {
                "l1_code": ["801010.SI", "801010.SI"],
                "l1_name": ["农林牧渔", "农林牧渔"],
                "ts_code": ["600598.SH", "000998.SZ"],
                "name": ["北大荒", "隆平高科"],
                "is_new": ["Y", "Y"],
            }
        ),
        "801030.SI": pd.DataFrame(
            {
                "l1_code": ["801030.SI", "801030.SI"],
                "l1_name": ["基础化工", "基础化工"],
                "ts_code": ["600309.SH", "002460.SZ"],
                "name": ["万华化学", "赣锋锂业"],
                "is_new": ["Y", "Y"],
            }
        ),
    }

    class FakePro:
        def index_classify(self, level, src):
            assert level == "L1"
            assert src == "SW2021"
            return industries

        def index_member_all(self, l1_code):
            return members[l1_code]

    df = industry.fetch_shenwan_mapping(FakePro())
    assert list(df.columns) == ["code", "industry_code", "industry_name"]
    assert len(df) == 4
    mapping = dict(zip(df["code"], df["industry_name"]))
    assert mapping["600598"] == "农林牧渔"
    assert mapping["000998"] == "农林牧渔"
    assert mapping["600309"] == "基础化工"
    assert mapping["002460"] == "基础化工"
    # industry_code 应为真实申万一级代码（去除 .SI 后缀）
    code_map = dict(zip(df["code"], df["industry_code"]))
    assert code_map["600598"] == "801010"
    assert code_map["600309"] == "801030"


def test_fetch_shenwan_industries_returns_l1_list():
    raw = pd.DataFrame(
        {
            "index_code": ["801010.SI", "801030.SI"],
            "industry_name": ["农林牧渔", "基础化工"],
        }
    )

    class FakePro:
        @staticmethod
        def index_classify(level, src):
            assert level == "L1"
            assert src == "SW2021"
            return raw

    df = industry.fetch_shenwan_industries(FakePro())
    assert list(df.columns) == ["index_code", "industry_name"]
    assert len(df) == 2
    assert df.iloc[0]["index_code"] == "801010.SI"
