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
            "close": [1500.0, 128.0, 10.0, 2.0],
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
        "trading_day",
        "stock_code",
        "stock_name",
        "pe_ttm",
        "pb",
        "dividend_yield",
        "total_mv",
        "circ_mv",
        "turnover_rate",
        "close",
    ]
    # 仅 date 参数走单日增量：trading_day 来自 date 参数归一化，不再由 executor 兜底注入
    assert set(df["trading_day"]) == {"20260827"}
    # total_mv 万元 → 元：210000.0 万元 * 10000 = 2.1e9 元
    row = df[df["stock_code"] == "600519"].iloc[0]
    assert row["total_mv"] == pytest.approx(2100000000.0)
    assert row["stock_name"] == "贵州茅台"
    assert row["close"] == pytest.approx(1500.0)  # MS-07：收盘价随快照落列


def test_stock_financial_normalizes_and_backfills(monkeypatch):
    # 用 monkeypatch 替换 _last_n_periods，隔离真实的 12 期逻辑
    monkeypatch.setattr(plugins, "_last_n_periods", lambda n: ["20260331", "20260630"])

    class FakePro:
        def fina_indicator(self, ts_code=None):
            assert ts_code is not None
            return _financial_stock_frame(ts_code)

        def income(self, ts_code=None, start_date=None, end_date=None, fields=None):
            return pd.DataFrame()  # 无 income 数据：revenue 全 NaN（MS-09）

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
        "revenue",
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
        "revenue",
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

        def income(self, ts_code=None, start_date=None, end_date=None, fields=None):
            return pd.DataFrame()  # 无 income 数据：revenue 全 NaN（MS-09）

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
        "revenue",
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

        def income(self, ts_code=None, start_date=None, end_date=None, fields=None):
            return pd.DataFrame()  # 无 income 数据：revenue 全 NaN（MS-09）

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

        def income(self, ts_code=None, start_date=None, end_date=None, fields=None):
            return pd.DataFrame()  # income 同为逐股接口（MS-09），每次上游调用前都须限速

        def stock_basic(self, list_status=None, fields=None):
            return _financial_stock_basic()

    waits = []

    class _FakeLimiter:
        def wait(self):
            waits.append(1)

    src = plugins.StockFinancialSource("sf", pro_factory=lambda: FakePro(), limiter=_FakeLimiter())
    src.fetch({})

    assert len(waits) == 4  # 2 个有效股 × (fina_indicator + income)，每次请求前都先限速


def _income_frame(ts_code):
    """income 探测样例（09-调研报告/2026-09-17-income接口校准.md 实测口径）：
    缺省查询只回 report_type='1'（合并报表），revenue 单位元；同一 end_date 的多行
    来自 update_flag 0/1 并存（而非 report_type 差异），ann_date 供并列去重溯源。"""
    rows = [
        # 20260630：update_flag=0 陈旧行；两条 flag=1 行靠 ann_date 分先后，去重须取 ann_date 更晚者
        {
            "ts_code": ts_code,
            "end_date": "20260630",
            "report_type": "1",
            "revenue": 7_000_000.0,
            "ann_date": "20260801",
            "update_flag": "0",
        },
        {
            "ts_code": ts_code,
            "end_date": "20260630",
            "report_type": "1",
            "revenue": 7_500_000.0,
            "ann_date": "20260820",
            "update_flag": "1",
        },
        {
            "ts_code": ts_code,
            "end_date": "20260630",
            "report_type": "1",
            "revenue": 8_000_000.0,
            "ann_date": "20260829",
            "update_flag": "1",
        },
    ]
    if ts_code == "600519.SH":
        # 20260331 仅 600519 有 income 数据（000858 该期走 NaN 路径）；
        # report_type='6'（母公司）是实测观察到的低值污染源，ann_date 给更晚以钉死客户端过滤
        rows += [
            {
                "ts_code": ts_code,
                "end_date": "20260331",
                "report_type": "1",
                "revenue": 4_000_000.0,
                "ann_date": "20260429",
                "update_flag": "1",
            },
            {
                "ts_code": ts_code,
                "end_date": "20260331",
                "report_type": "6",
                "revenue": 3_000_000.0,
                "ann_date": "20260520",
                "update_flag": "1",
            },
        ]
    return pd.DataFrame(rows)


def test_stock_financial_merges_income_revenue(monkeypatch):
    """income 并入营收（MS-09）：合并口径过滤 + update_flag/ann_date 去重 + 元单位直通 + 无匹配留 NaN。"""
    monkeypatch.setattr(plugins, "_last_n_periods", lambda n: ["20260331", "20260630"])
    # 裁决定稿的实测契约：fields 必含去重所需的 ann_date/update_flag；revenue 单位为元 → 系数 1.0
    assert plugins.INCOME_FIELDS == "ts_code,end_date,report_type,revenue,ann_date,update_flag"
    assert plugins.INCOME_REVENUE_SCALE == 1.0

    income_fetched = []

    class FakePro:
        def fina_indicator(self, ts_code=None):
            assert ts_code is not None
            return _financial_stock_frame(ts_code)

        def income(self, ts_code=None, start_date=None, end_date=None, fields=None):
            assert ts_code is not None
            # 调研实测：income 的 start/end 是公告日窗口不可靠，禁止传参限窗（客户端按 end_date 截断）
            assert start_date is None and end_date is None
            income_fetched.append(ts_code)
            return _income_frame(ts_code)

        def stock_basic(self, list_status=None, fields=None):
            return _financial_stock_basic()

    src = plugins.StockFinancialSource("sf", pro_factory=lambda: FakePro())
    df = src.fetch({})

    # 手推行数：2 个有效股 × 2 个报告期 = 4 行；income 去重后 end_date 唯一，左并合并不增行
    assert len(df) == 4
    assert "revenue" in df.columns
    assert sorted(income_fetched) == ["000858.SZ", "600519.SH"]  # 每个有效股都拉了 income
    # 20260630：去重取 update_flag 最大、并列 ann_date 更晚的行（8M）；0 行（7M）与并列早行（7.5M）均不污染
    row = df[(df["stock_code"] == "600519") & (df["report_date"] == "20260630")].iloc[0]
    assert row["revenue"] == pytest.approx(8_000_000.0 * 1.0)
    row_858 = df[(df["stock_code"] == "000858") & (df["report_date"] == "20260630")].iloc[0]
    assert row_858["revenue"] == pytest.approx(8_000_000.0 * 1.0)
    # 20260331：母公司口径行（report_type='6'，ann_date 更晚）被客户端过滤，只取合并口径 4M
    row_q1 = df[(df["stock_code"] == "600519") & (df["report_date"] == "20260331")].iloc[0]
    assert row_q1["revenue"] == pytest.approx(4_000_000.0 * 1.0)
    # fina_indicator 有、income 无匹配（000858 的 20260331）：revenue 为 NaN，不阻断其余列
    row_q1_858 = df[(df["stock_code"] == "000858") & (df["report_date"] == "20260331")].iloc[0]
    assert pd.isna(row_q1_858["revenue"])


def test_stock_financial_income_all_filtered_out_keeps_revenue_nan(monkeypatch):
    """issue #39 二组：income 有行但全被过滤 → revenue 走 NaN，不阻断其余指标。

    两条真实过滤路径各锚定一股：600519 全部 report_type='6'（母公司）被合并口径
    过滤清空；000858 合并口径但报告期全在截断前（20251231 < cutoff 20260331）被
    end_date 过滤清空。income 原始帧非空、过滤后为空，revenue 须全 NaN 且行不丢。"""
    monkeypatch.setattr(plugins, "_last_n_periods", lambda n: ["20260331", "20260630"])

    def _all_filtered_income_frame(ts_code):
        if ts_code == "600519.SH":
            # 全部 report_type='6'：被 report_type == INCOME_MERGED_REPORT_TYPE 过滤清空
            rows = [
                {
                    "ts_code": ts_code,
                    "end_date": end_date,
                    "report_type": "6",
                    "revenue": 3_000_000.0,
                    "ann_date": "20260520",
                    "update_flag": "1",
                }
                for end_date in ("20260331", "20260630")
            ]
        else:
            # 合并口径但 end_date 全在 cutoff 前：被 end_date >= cutoff 过滤清空
            rows = [
                {
                    "ts_code": ts_code,
                    "end_date": "20251231",
                    "report_type": "1",
                    "revenue": 5_000_000.0,
                    "ann_date": "20260430",
                    "update_flag": "1",
                }
            ]
        return pd.DataFrame(rows)

    class FakePro:
        def fina_indicator(self, ts_code=None):
            return _financial_stock_frame(ts_code)

        def income(self, ts_code=None, start_date=None, end_date=None, fields=None):
            return _all_filtered_income_frame(ts_code)

        def stock_basic(self, list_status=None, fields=None):
            return _financial_stock_basic()

    src = plugins.StockFinancialSource("sf", pro_factory=lambda: FakePro())
    df = src.fetch({})

    # fina_indicator 的行不因 income 全被过滤而丢失：2 股 × 2 期 = 4 行
    assert len(df) == 4
    assert df["revenue"].isna().all()  # 过滤后 income 为空 → revenue 全 NaN
    assert df["roe"].notna().all()  # 其余指标照常并入，不被阻断


@pytest.mark.parametrize(
    "first_revenue,second_revenue",
    [(7_500_000.0, 8_000_000.0), (8_000_000.0, 7_500_000.0)],
)
def test_stock_financial_income_full_tie_prefers_first_appearing_row(monkeypatch, first_revenue, second_revenue):
    """issue #39 二组：update_flag 与 ann_date 完全并列时的决胜语义显式化。

    调研定稿规则只定义到「update_flag 最大者、并列时 ann_date 更晚者」；两键完全并列时
    现状未定义，且 sort_values 默认 quicksort 非稳定——胜者随输入行序漂移（组内并列行
    多于两条时，胜者甚至可能是任意一行）。现显式定义为稳定语义：完全并列时保持原始
    出现顺序，先出现的行胜。双向用例：调换两条候选并列行的输入顺序，胜者必须恒为
    先出现者，与输入行序无关。"""
    monkeypatch.setattr(plugins, "_last_n_periods", lambda n: ["20260331", "20260630"])

    def _tied_income_frame(ts_code):
        # end_date/report_type/update_flag/ann_date 全同的并列组，仅 revenue 不同：
        # 两条候选行置首（双向调序），同键填充行殿后把组撑出小数组插入排序的稳定区，
        # 使 quicksort 的并列乱序可复现暴露。
        rows = [
            {
                "ts_code": ts_code,
                "end_date": "20260630",
                "report_type": "1",
                "revenue": first_revenue,
                "ann_date": "20260820",
                "update_flag": "1",
            },
            {
                "ts_code": ts_code,
                "end_date": "20260630",
                "report_type": "1",
                "revenue": second_revenue,
                "ann_date": "20260820",
                "update_flag": "1",
            },
        ]
        rows += [
            {
                "ts_code": ts_code,
                "end_date": "20260630",
                "report_type": "1",
                "revenue": 1_000_000.0,
                "ann_date": "20260820",
                "update_flag": "1",
            }
        ] * 3
        return pd.DataFrame(rows)

    class FakePro:
        def fina_indicator(self, ts_code=None):
            return _financial_stock_frame(ts_code)

        def income(self, ts_code=None, start_date=None, end_date=None, fields=None):
            # 仅 600519 有并列组；000858 空 income 走全 NaN 路径（既有用例已覆盖）
            return _tied_income_frame(ts_code) if ts_code == "600519.SH" else pd.DataFrame()

        def stock_basic(self, list_status=None, fields=None):
            return _financial_stock_basic()

    src = plugins.StockFinancialSource("sf", pro_factory=lambda: FakePro())
    df = src.fetch({})

    row = df[(df["stock_code"] == "600519") & (df["report_date"] == "20260630")].iloc[0]
    # 完全并列先者胜：无论两条候选行谁先到，恒取首行 revenue（× 单位系数 1.0）
    assert row["revenue"] == pytest.approx(first_revenue * plugins.INCOME_REVENUE_SCALE)


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
