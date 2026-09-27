"""P1-6 码表/URL/限速外置到集中常量模块。

目标：散落在 plugins.py 的运营级常量（指数/行业码表、国债期限、上游 URL、限速）
收拢到 collector/sources/constants.py 一处；plugins 统一引用。本测试锚定关键值，
防止后续挪动时静默丢失或变形；完整行为回归由既有套件（test_plugins 等）承担。
"""

import datetime as dt

from collector.sources import constants as c


def test_index_code_tables():
    assert c.INDEX_CODES == {
        "000016": "上证50",
        "000300": "沪深300",
        "000905": "中证500",
        "399006": "创业板指",
        "000688": "科创50",
    }
    assert set(c.INDEX_CLOSE_CODES) == {"000300", "000905", "930950"}
    assert c.INDEX_CLOSE_TS["930950"] == "930950.CSI"


def test_industry_index_codes_31():
    assert len(c.INDUSTRY_INDEX_CODES) == 31
    assert c.INDUSTRY_INDEX_CODES["801010"] == "农林牧渔"
    assert c.INDUSTRY_INDEX_CODES["801980"] == "美容护理"


def test_bond_and_gold_proxy_codes():
    assert c.BOND_INDEX_TS_CODE == "H11001.CSI"
    assert (c.BOND_INDEX_CODE, c.BOND_INDEX_NAME) == ("H11001", "中证全债")
    assert c.GOLD_ETF_TS_CODE == "518880.SH"
    assert (c.GOLD_ETF_CODE, c.GOLD_ETF_NAME) == ("518880", "华安黄金ETF")


def test_treasury_terms_and_window():
    assert [t for t, _ in c.TERMS] == ["1Y", "3Y", "5Y", "10Y", "30Y"]
    assert dt.date(2006, 1, 1) == c.CURVE_EARLIEST
    assert c.CURVE_CHUNK_DAYS == 180


def test_etf_upstream_endpoint():
    assert "fundmobapi.eastmoney.com" in c.ETF_DETAIL_URL
    assert c.ETF_DETAIL_HEADERS["Referer"] == "https://fund.eastmoney.com/"
    assert c.ETF_DETAIL_TIMEOUT == 15


def test_rate_limit_constants():
    assert c.FINANCIAL_MIN_INTERVAL == 0.35
    assert c.STOCK_DAILY_MIN_INTERVAL == 0.35
    assert c.ETF_ENRICH_INTERVAL == 0.3
    assert c.ETF_DAILY_INTERVAL == 0.3
    assert c.INDEX_DAILY_INTERVAL == 0.3


def test_recent_open_lookback():
    assert c.RECENT_OPEN_LOOKBACK_DAYS == 15


def test_plugins_reference_central_module():
    """plugins 不再自带这些常量定义（引用集中模块）——防回潮。"""
    import inspect

    import collector.sources.plugins as plugins

    src = inspect.getsource(plugins)
    for name in ("INDEX_CODES =", "INDUSTRY_INDEX_CODES = {", "ETF_DETAIL_URL = (", "TERMS = ["):
        assert name not in src, f"plugins.py 仍定义 {name!r}，应引用 constants"
