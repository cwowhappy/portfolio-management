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


# ---------------------------------------------------------------- MS-07 基准指数收盘价 index_close


from collector.sources.plugins import IndexCloseSource


def test_index_close_fetches_three_benchmarks(mocker):
    pro = mocker.MagicMock()
    pro.index_daily.return_value = pd.DataFrame({"trade_date": ["20260915", "20260914"], "close": [3900.12, 3890.5]})
    src = IndexCloseSource("index_close", pro_factory=lambda: pro)
    df = src.fetch({"start": "2026-09-01", "end": "2026-09-16"})

    assert pro.index_daily.call_count == 3  # 三只基准各一次
    pro.index_daily.assert_any_call(ts_code="000300.SH", start_date="20260901", end_date="20260916")
    pro.index_daily.assert_any_call(ts_code="930950.CSI", start_date="20260901", end_date="20260916")
    assert list(df.columns) == ["trading_day", "index_code", "index_name", "close"]
    assert set(df["index_code"]) == {"000300", "000905", "930950"}
    assert df[df["index_code"] == "000300"]["close"].tolist() == [3900.12, 3890.5]


def test_index_close_supports_range_for_backfill():
    assert IndexCloseSource.supports_range is True


# ---------------------------------------------------------------- MS-07 个股估值日快照 close + 区间回填

from collector.sources.plugins import StockValuationDailySource


class _NoWaitLimiter:
    def wait(self):
        pass


def _svd_pro(single_day):
    class FakePro:
        def trade_cal(self, exchange=None, start_date=None, end_date=None, is_open=None):
            import pandas as pd

            return pd.DataFrame({"cal_date": ["20260914", "20260915"], "is_open": ["1", "1"]})

        def stock_basic(self, list_status=None, fields=None):
            import pandas as pd

            return pd.DataFrame({"ts_code": ["600519.SH"], "name": ["贵州茅台"]})

        def daily_basic(self, trade_date=None):
            import pandas as pd

            return pd.DataFrame(
                {
                    "ts_code": ["600519.SH"],
                    "pe_ttm": [30.0],
                    "pb": [9.0],
                    "dv_ttm": [1.0],
                    "total_mv": [1000.0],
                    "circ_mv": [900.0],
                    "turnover_rate": [0.5],
                    "close": [1500.0],
                }
            )

    return FakePro()


def test_stock_valuation_daily_includes_close():
    src = StockValuationDailySource("svd", pro_factory=lambda: _svd_pro(True))
    df = src.fetch({"date": "2026-09-15"})
    assert "close" in df.columns
    assert df["close"].tolist() == [1500.0]


def test_stock_valuation_daily_range_loops_open_days():
    src = StockValuationDailySource("svd", pro_factory=lambda: _svd_pro(True), limiter=_NoWaitLimiter())
    df = src.fetch({"start": "2026-09-14", "end": "2026-09-15"})
    assert len(df) == 2  # 两个开市日各一行


# ---------------------------------------------------------------- MS-13 申万一级行业指数收盘 industry_index_close


def test_industry_index_close_fetches_all_industries(mocker):
    from collector.sources.plugins import INDUSTRY_INDEX_CODES, IndustryIndexCloseSource

    captured = []

    def fake_sw_fetch(code):
        captured.append(code)
        return pd.DataFrame(
            {
                "日期": ["2026-09-22", "2026-09-23"],
                "收盘": [1234.0, 1235.5],
            }
        )

    src = IndustryIndexCloseSource("industry_index_close", sw_fetch=fake_sw_fetch, sleep_fn=lambda s: None)
    df = src.fetch({"start": "2026-09-23", "end": "2026-09-23"})
    assert len(captured) == len(INDUSTRY_INDEX_CODES) == 31
    assert set(df.columns) == {"trading_day", "index_code", "index_name", "close"}
    assert len(df) == 31  # 窗口裁剪：只留 2026-09-23 一天/行业
    assert (df["trading_day"] == "20260923").all()  # ISO → 紧凑格式归一
    assert df["index_code"].str.endswith(".SI").sum() == 0  # 去后缀落库，与 shenwan_industry_mapping 同构


def test_industry_index_close_supports_range_for_backfill():
    from collector.sources.plugins import IndustryIndexCloseSource

    assert IndustryIndexCloseSource.supports_range is True


# ------------------------------------------------- MS-13 中证全债/黄金ETF收盘 bond_index_close / gold_etf_close


def test_bond_index_close_maps_columns(mocker):
    from collector.sources.plugins import BondIndexCloseSource

    class FakePro:
        def index_daily(self, ts_code, start_date, end_date):
            assert ts_code == "H11001.CSI"
            return pd.DataFrame({"trade_date": ["20260923"], "close": [3456.7]})

    df = BondIndexCloseSource("bond_index_close", pro_factory=lambda: FakePro()).fetch(
        {"start": "2026-09-23", "end": "2026-09-23"}
    )
    assert set(df.columns) == {"trading_day", "index_code", "index_name", "close"}
    assert df.iloc[0]["index_code"] == "H11001" and df.iloc[0]["index_name"] == "中证全债"


def test_bond_index_close_supports_range_for_backfill():
    from collector.sources.plugins import BondIndexCloseSource

    assert BondIndexCloseSource.supports_range is True


def test_gold_etf_close_maps_columns(mocker):
    from collector.sources.plugins import GoldEtfCloseSource

    class FakePro:
        def fund_daily(self, ts_code, start_date, end_date):
            assert ts_code == "518880.SH"
            return pd.DataFrame({"trade_date": ["20260923"], "close": [7.89]})

    df = GoldEtfCloseSource("gold_etf_close", pro_factory=lambda: FakePro()).fetch(
        {"start": "2026-09-23", "end": "2026-09-23"}
    )
    assert df.iloc[0]["index_code"] == "518880" and df.iloc[0]["index_name"] == "华安黄金ETF"


def test_gold_etf_close_supports_range_for_backfill():
    from collector.sources.plugins import GoldEtfCloseSource

    assert GoldEtfCloseSource.supports_range is True


# ------------------------------------- MS-14 P3 Task 13 ETF 目录/费率/规模 etf_basic


def _etf_catalog_frame():
    """新浪目录样例（实测 1685×13，代码带 sh/sz 前缀，名称列为 enrich 失败时的回退名）。"""
    return pd.DataFrame(
        {
            "代码": [
                "sh510300",
                "sz159915",
                "sh518880",
                "sh511860",
                "sh513100",
                "sh511010",
                "sz159985",
                "sh512880",
            ],
            "名称": ["300ETF", "创业板ETF", "黄金ETF", "货币ETF", "纳指ETF", "国债ETF", "豆粕ETF", "银行ETF"],
        }
    )


def _etf_detail_map():
    """天天基金移动端 FundMNDetailInformation Datas 实测样例（2026-09-26 探测报告 §2.2）。"""
    return {
        "510300": {  # 宽基：费率合计 0.15+0.05，规模 元→亿元
            "FCODE": "510300",
            "SHORTNAME": "沪深300ETF华泰柏瑞",
            "FTYPE": "指数型-股票",
            "INDEXCODE": "000300",
            "INDEXNAME": "沪深300指数",
            "MGREXP": "0.15%",
            "TRUSTEXP": "0.05%",
            "ENDNAV": "94872183996.4",
        },
        "159915": {  # 宽基：ENDNAV 缺失（"--"）→ scale NaN 不阻断
            "FCODE": "159915",
            "SHORTNAME": "创业板ETF易方达",
            "FTYPE": "指数型-股票",
            "INDEXCODE": "399006",
            "INDEXNAME": "创业板指数(价格)",
            "MGREXP": "0.50%",
            "TRUSTEXP": "0.10%",
            "ENDNAV": "--",
        },
        "518880": {  # 商品：INDEXCODE=AU9999（SGE 现货）→ tracking 双列 null
            "FCODE": "518880",
            "SHORTNAME": "黄金ETF华安",
            "FTYPE": "指数型-其他",
            "INDEXCODE": "AU9999",
            "INDEXNAME": "黄金9999",
            "MGREXP": "0.50%",
            "TRUSTEXP": "0.10%",
            "ENDNAV": "86208039412.35",
        },
        "511860": {  # 货币：INDEXCODE "--" → tracking 双列 null，FTYPE 货币 → category 商品
            "FCODE": "511860",
            "SHORTNAME": "货币ETF博时",
            "FTYPE": "货币型-普通货币",
            "INDEXCODE": "--",
            "INDEXNAME": "--",
            "MGREXP": "0.30%",
            "TRUSTEXP": "0.09%",
            "ENDNAV": "40663587.14",
        },
        "513100": {  # QDII：海外指数码 NDX100 非证券指数码但属证券指数 → 保留展示
            "FCODE": "513100",
            "SHORTNAME": "纳指ETF国泰",
            "FTYPE": "指数型-海外股票",
            "INDEXCODE": "NDX100",
            "INDEXNAME": "纳斯达克100指数",
            "MGREXP": "0.60%",
            "TRUSTEXP": "0.20%",
            "ENDNAV": "19468268721.53",
        },
        "511010": {  # 债券：FTYPE 固收（不含「债」字）→ 债券
            "FCODE": "511010",
            "SHORTNAME": "国债ETF国泰",
            "FTYPE": "指数型-固收",
            "INDEXCODE": "000140",
            "INDEXNAME": "上证5年期国债指数",
            "MGREXP": "0.15%",
            "TRUSTEXP": "0.05%",
            "ENDNAV": "5117244931.52",
        },
        "159985": {  # 商品：商品期货指数（DCESMFI/期货价格指数）→ tracking 双列 null + 商品
            "FCODE": "159985",
            "SHORTNAME": "豆粕ETF华夏",
            "FTYPE": "指数型-其他",
            "INDEXCODE": "DCESMFI",
            "INDEXNAME": "大商所豆粕期货价格指数",
            "MGREXP": "0.50%",
            "TRUSTEXP": "0.10%",
            "ENDNAV": "2978370883.87",
        },
    }


def _etf_source(sleep_log=None):
    from collector.sources.plugins import EtfBasicSource

    details = _etf_detail_map()

    def fake_detail(code):
        if code == "512880":
            raise TimeoutError("enrich 单只失败样例")  # 不阻断整批
        return details[code]

    return EtfBasicSource(
        "etf_basic",
        catalog_fetch=lambda: _etf_catalog_frame(),
        detail_fetch=fake_detail,
        sleep_fn=(sleep_log.append if sleep_log is not None else (lambda s: None)),
    )


def test_etf_basic_maps_columns_and_category_rules():
    """七列契约 + 费率合计/规模换算/缺失置 NaN/非证券指数码置 null/category 规则。"""
    df = _etf_source().fetch({})
    assert set(df.columns) == {
        "fund_code",
        "fund_name",
        "fee_rate",
        "scale",
        "tracking_index_code",
        "tracking_index_name",
        "category",
    }
    by_code = df.set_index("fund_code")

    # 宽基 510300：费率 0.15+0.05=0.20；规模 94872183996.4 元 → 948.7218 亿
    assert by_code.loc["510300", "fund_name"] == "沪深300ETF华泰柏瑞"
    assert by_code.loc["510300", "fee_rate"] == 0.20
    assert round(by_code.loc["510300", "scale"], 4) == 948.7218
    assert by_code.loc["510300", "tracking_index_code"] == "000300"
    assert by_code.loc["510300", "tracking_index_name"] == "沪深300指数"
    assert by_code.loc["510300", "category"] == "宽基"

    # 宽基 159915：ENDNAV 缺失 → scale 为 NaN（不阻断）
    assert pd.isna(by_code.loc["159915", "scale"])
    assert by_code.loc["159915", "fee_rate"] == 0.60  # 0.50+0.10
    assert by_code.loc["159915", "category"] == "宽基"

    # 商品 518880：AU9999 非证券指数 → tracking 双列 null；黄金9999 → 商品
    assert pd.isna(by_code.loc["518880", "tracking_index_code"])
    assert pd.isna(by_code.loc["518880", "tracking_index_name"])
    assert by_code.loc["518880", "category"] == "商品"
    assert by_code.loc["518880", "fee_rate"] == 0.60

    # 货币 511860：INDEXCODE "--" → tracking 双列 null；FTYPE 货币 → 商品（口径见任务 13 brief）
    assert pd.isna(by_code.loc["511860", "tracking_index_code"])
    assert by_code.loc["511860", "category"] == "商品"
    assert round(by_code.loc["511860", "scale"], 4) == 0.4066  # 40663587.14 元 → 亿

    # QDII 513100：FTYPE 海外 → QDII；NDX100 海外证券指数码保留展示（误差计算侧自然降级）
    assert by_code.loc["513100", "category"] == "QDII"
    assert by_code.loc["513100", "tracking_index_code"] == "NDX100"
    assert by_code.loc["513100", "tracking_index_name"] == "纳斯达克100指数"

    # 债券 511010：FTYPE 固收 → 债券
    assert by_code.loc["511010", "category"] == "债券"

    # 商品期货 159985：DCESMFI（大商所豆粕期货价格指数）→ tracking 双列 null + 商品
    assert pd.isna(by_code.loc["159985", "tracking_index_code"])
    assert pd.isna(by_code.loc["159985", "tracking_index_name"])
    assert by_code.loc["159985", "category"] == "商品"


def test_etf_basic_enrich_failure_keeps_row_with_catalog_fallback():
    """单只 enrich 失败/超时：该行费率/规模/指数 null 不阻断整批；fund_name 回退新浪目录名，
    category 退化为基金名关键词推断（512880 银行ETF → 行业）。"""
    df = _etf_source().fetch({})
    assert len(df) == 8  # 失败行保留，整批 8 行
    row = df[df["fund_code"] == "512880"].iloc[0]
    assert row["fund_name"] == "银行ETF"  # SHORTNAME 缺失 → 目录名称列兜底
    assert pd.isna(row["fee_rate"])
    assert pd.isna(row["scale"])
    assert pd.isna(row["tracking_index_code"])
    assert row["category"] == "行业"


def test_etf_basic_fee_rate_requires_both_parts():
    """费率=管理+托管合计：任一部分缺失/不可解析 → null（宁缺毋低估，防费率筛选漏杀）。"""
    from collector.sources.plugins import _etf_fee_rate

    assert _etf_fee_rate({"MGREXP": "0.50%", "TRUSTEXP": "0.10%"}) == 0.60
    assert _etf_fee_rate({"MGREXP": "0.50%", "TRUSTEXP": "--"}) is None
    assert _etf_fee_rate({"MGREXP": "--", "TRUSTEXP": "--"}) is None
    assert _etf_fee_rate({}) is None


def test_etf_basic_category_rule_constants():
    """category 常量规则钉住（探测报告 §2.2 七品类实测口径）。"""
    from collector.sources.plugins import _etf_category

    assert _etf_category("指数型-股票", "沪深300指数") == "宽基"
    assert _etf_category("指数型-股票", "中证银行指数") == "行业"
    assert _etf_category("指数型-股票", "上证科创板50成份指数") == "宽基"  # 科创板=宽基非行业
    assert _etf_category("指数型-股票", "中证半导体指数") == "行业"
    assert _etf_category("指数型-海外股票", "纳斯达克100指数") == "QDII"
    assert _etf_category(None, None, "纳指ETF") == "QDII"  # 基金名兜底
    assert _etf_category("指数型-固收", "上证5年期国债指数") == "债券"
    assert _etf_category("指数型-其他", "黄金9999") == "商品"
    assert _etf_category("货币型-普通货币", "--") == "商品"  # 货币口径归商品（brief 定则）
    assert _etf_category(None, None) == "其他"  # 无任何分类信号
    assert _etf_category("指数型-股票", "中证红利指数") == "宽基"  # 策略指数归宽基


def test_etf_basic_sleeps_between_enrich_calls():
    """逐只 enrich 之间保守限速 0.3s（~1685 只 ≈ 9 分钟周更，探测报告 §2.2）。"""
    sleeps = []
    _etf_source(sleep_log=sleeps).fetch({})
    assert sleeps == [0.3] * 8  # 目录 8 只逐只 enrich


def test_etf_basic_supports_range_false():
    from collector.sources.plugins import EtfBasicSource

    assert EtfBasicSource.supports_range is False
