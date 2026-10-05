"""P1-9 stock_valuation_daily akshare 备源。

已确认方案 a：akshare spot_em 转换（接口已在用、research/02 实测），追加为第二备源。
妥协口径（已确认接受）：dividend_yield=NULL（spot 无 dv_ttm）、市盈率-动态 近似 pe_ttm
（动态口径非 TTM）——仅主源失败日写入，tushare 恢复后 upsert 覆盖回 TTM。
supports_range=False：spot 是实时快照，只救当日增量；指定历史 date 直接拒绝（防错日期写入）。
"""

import datetime as dt
from types import SimpleNamespace

import pandas as pd
import pytest

from collector.sources.base import SourceError
from collector.sources.plugins import StockValuationDailyBackupSource, make_open_day_check

EXPECTED_COLUMNS = [
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


def _spot_frame():
    """akshare spot_em 同构样例：正常股 / ST / 退市 / 北交所（43/83 段）各一。"""
    return pd.DataFrame(
        {
            "代码": ["600519", "000001", "002594", "830799", "600056"],
            "名称": ["贵州茅台", "平安银行", "比亚迪", "ST个股", "退市股"],
            "最新价": [1500.0, 10.0, 250.0, 3.0, 1.0],
            "换手率": [0.5, 1.2, 2.0, 5.0, 0.1],
            "市盈率-动态": [25.0, 4.0, 30.0, None, -5.0],
            "市净率": [8.0, 0.6, 5.0, None, 0.5],
            "总市值": [1.88e12, 1.95e11, 7.3e11, 2.1e9, 1.0e9],
            "流通市值": [1.88e12, 1.9e11, 6.0e11, 1.0e9, 0.8e9],
        }
    )


def _source(spot=None):
    return StockValuationDailyBackupSource(
        "stock_valuation_daily_backup", spot_fetch=lambda: spot if spot is not None else _spot_frame()
    )


def test_column_contract_and_dividend_null():
    df = _source().fetch({})
    assert list(df.columns) == EXPECTED_COLUMNS
    assert df["dividend_yield"].isna().all()
    row = df[df["stock_code"] == "600519"].iloc[0]
    assert row["pe_ttm"] == 25.0  # 市盈率-动态 近似 pe_ttm（口径妥协，类注释注明）
    assert row["close"] == 1500.0
    assert row["turnover_rate"] == 0.5
    assert row["total_mv"] == 1.88e12  # spot_em 总市值为元口径，直通（主源 tushare 万元 ×10000 换算）
    assert row["circ_mv"] == 1.88e12


def test_filters_st_delisted_and_beijing():
    df = _source().fetch({})
    codes = set(df["stock_code"])
    assert codes == {"600519", "000001", "002594"}  # 剔 ST 名称、退市、北交所 83 段
    assert (df["stock_name"].str.contains("ST|退") == False).all()  # noqa: E712


def test_today_snapshot_trading_day():
    df = _source().fetch({})
    assert set(df["trading_day"]) == {dt.date.today().strftime("%Y%m%d")}


def test_explicit_today_date_param_ok():
    today = dt.date.today().isoformat()
    df = _source().fetch({"date": today})
    assert set(df["trading_day"]) == {dt.date.today().strftime("%Y%m%d")}


def test_historical_date_rejected():
    """spot 是实时快照：历史日期无法提供，拒绝而非写错日期（防备源污染历史行）。"""
    with pytest.raises(SourceError, match="仅支持当日"):
        _source().fetch({"date": "2026-09-01"})


def test_supports_range_false():
    assert StockValuationDailyBackupSource.supports_range is False


def test_market_cap_magnitude_guard():
    """上游单位变更（如总市值改亿元）→ 量级防御拒绝，不污染落库。"""
    bad = _spot_frame()
    bad["总市值"] = bad["总市值"] / 1e8  # 模拟单位漂移到亿元
    with pytest.raises(SourceError, match="量级"):
        _source(spot=bad).fetch({})


def test_magnitude_guard_off_when_all_nan():
    empty_mv = _spot_frame()
    empty_mv["总市值"] = None
    df = _source(spot=empty_mv).fetch({})  # 全空市值不触发防御（median NaN）
    assert df["total_mv"].isna().all()


def test_yaml_declares_backup_source():
    import yaml

    with open("tasks/stock_valuation_daily.yaml") as f:
        defs = yaml.safe_load(f)
    classes = [s.get("class") for s in defs["source_ids"]]
    assert classes == ["stock_valuation_daily", "stock_valuation_daily_backup"]


# ---- MS-28 P2-C5 节假日空帧保护 ----


def test_holiday_empty_frame_and_spot_not_fetched():
    """非开市日：返回空帧（照 EtfCloseSource 语义——空帧 0 行，不写非交易日行），
    spot_fetch 不被消费。"""
    calls = []

    def _spot():
        calls.append(1)
        return _spot_frame()

    src = StockValuationDailyBackupSource(
        "stock_valuation_daily_backup", spot_fetch=_spot, is_open_day=lambda day: False
    )
    df = src.fetch({"date": dt.date.today().isoformat()})
    assert df.empty
    assert list(df.columns) == EXPECTED_COLUMNS
    assert calls == []


def test_open_day_injection_keeps_behavior():
    """开市日：注入判定为开市 → 现行为照旧（拉快照、写今天行）。"""
    src = StockValuationDailyBackupSource(
        "stock_valuation_daily_backup", spot_fetch=lambda: _spot_frame(), is_open_day=lambda day: True
    )
    df = src.fetch({})
    assert set(df["trading_day"]) == {dt.date.today().strftime("%Y%m%d")}
    assert set(df["stock_code"]) == {"600519", "000001", "002594"}


def test_make_open_day_check_calendar():
    """开市判定 wiring：trade_cal 单日 is_open 0/1 → True/False。"""

    def fake_pro(is_open):
        return SimpleNamespace(
            trade_cal=lambda **kw: pd.DataFrame({"cal_date": [kw["start_date"]], "is_open": [is_open]})
        )

    assert make_open_day_check(lambda: fake_pro(1))("20261009")  # 开市日
    assert not make_open_day_check(lambda: fake_pro(0))("20261001")  # 国庆闭市


def test_make_open_day_check_unavailable_proceeds():
    """日历不可得（tushare 故障/空响应）→ 视为开市照常快照：备源本为 tushare 故障兜底，
    不能因 trade_cal 失败自废（退回注入前行为）。"""

    def _boom():
        raise RuntimeError("tushare down")

    assert make_open_day_check(_boom)("20261001")
    assert make_open_day_check(lambda: SimpleNamespace(trade_cal=lambda **kw: pd.DataFrame()))("20261001")
