import datetime as dt
from types import SimpleNamespace
from unittest import mock

import pandas as pd

import collector.run_once as run_once
from collector.sources.index import INDEX_CODES


def test_collect_once_wires_sources_calc_store():
    today = dt.date.today()
    conn = mock.Mock()
    config = SimpleNamespace(tushare_token="token-123")

    # universe has name; mapping has NO name column (Task 2 fix)
    universe = pd.DataFrame(
        {
            "code": ["600519", "000858", "300750"],
            "name": ["贵州茅台", "五粮液", "宁德时代"],
            "pe": [25.0, 20.0, 30.0],
            "pb": [6.0, 4.0, 5.0],
            "market_cap": [2.1e12, 5.0e11, 1.0e12],
        }
    )
    # mapping 携带真实申万一级行业代码 industry_code
    mapping = pd.DataFrame(
        {
            "code": ["600519", "000858", "999999"],  # 999999 不在 universe 中，用于测试 code 兜底
            "industry_code": ["801120", "801120", "801170"],
            "industry_name": ["食品饮料", "食品饮料", "未知行业"],
        }
    )
    snapshot = {
        "pe_median": 20.0,
        "pb_median": 5.0,
        "net_breaker_count": 1,
        "net_breaker_ratio": 0.1,
    }
    industry_rows = [{"industry_code": "801120", "industry_name": "食品饮料", "pe": 22.0, "pb": 5.0}]
    index_df = pd.DataFrame(
        {
            "trading_day": [today.strftime("%Y%m%d")],
            "pe": [12.3],
            "pb": [1.5],
            "dividend_yield": [2.1],
        }
    )
    fake_pro = mock.Mock()
    calls = []

    def make(name, value):
        m = mock.Mock()

        def side(*_args, **_kwargs):
            calls.append(name)
            return value

        m.side_effect = side
        return m

    ups_snap = make("upsert_snapshot", None)
    ups_treas = make("upsert_treasury", None)
    ups_ind = make("upsert_industry", None)
    ups_map = make("upsert_mapping", None)
    ups_idx = make("upsert_index", None)

    with mock.patch.object(run_once, "fetch_all_a_valuation", make("fetch_all_a_valuation", universe)), mock.patch.object(
        run_once, "compute_snapshot", make("compute_snapshot", snapshot)
    ), mock.patch.object(
        run_once, "fetch_treasury_10y", make("fetch_treasury_10y", 2.21)
    ), mock.patch.object(
        run_once, "upsert_snapshot", ups_snap
    ), mock.patch.object(
        run_once, "upsert_treasury", ups_treas
    ), mock.patch.object(
        run_once.ts, "pro_api", make("pro_api", fake_pro)
    ), mock.patch.object(
        run_once, "fetch_index_valuation", make("fetch_index_valuation", index_df)
    ), mock.patch.object(
        run_once, "upsert_index", ups_idx
    ), mock.patch.object(
        run_once, "fetch_shenwan_mapping", make("fetch_shenwan_mapping", mapping)
    ), mock.patch.object(
        run_once, "compute_industry_valuation", make("compute_industry_valuation", industry_rows)
    ), mock.patch.object(
        run_once, "upsert_industry", ups_ind
    ), mock.patch.object(
        run_once, "upsert_mapping", ups_map
    ):
        run_once.collect_once(conn, config)

    # 顺序：免费数据（快照/国债）先落库 → tushare 段（每个指数 估值+落库 交替 → 行业）
    expected_calls = (
        ["fetch_all_a_valuation", "compute_snapshot", "fetch_treasury_10y",
         "upsert_snapshot", "upsert_treasury", "pro_api"]
        + ["fetch_index_valuation", "upsert_index"] * len(INDEX_CODES)
        + ["fetch_shenwan_mapping", "compute_industry_valuation", "upsert_industry", "upsert_mapping"]
    )
    assert calls == expected_calls

    ups_snap.assert_called_once_with(conn, today, snapshot)
    ups_treas.assert_called_once_with(conn, today, 2.21)
    ups_ind.assert_called_once_with(conn, today, industry_rows)
    # 每日指数：对每个 INDEX_CODES 代码 upsert 一次当日估值
    assert ups_idx.call_count == len(INDEX_CODES)

    # 行业映射：stock_name 取 universe join（universe 有 name），缺失则回退 code；
    # industry_code 用真实申万一级代码（801120/801170）。
    expected_mapping_rows = [
        ("600519", "贵州茅台", "801120", "食品饮料"),
        ("000858", "五粮液", "801120", "食品饮料"),
        ("999999", "999999", "801170", "未知行业"),
    ]
    ups_map.assert_called_once_with(conn, expected_mapping_rows)


def test_collect_once_tushare_failure_still_persists_snapshot_and_treasury():
    """tushare 段失败应降级：快照/国债仍落库，且不抛异常。"""
    today = dt.date.today()
    conn = mock.Mock()
    config = SimpleNamespace(tushare_token="token-123")

    universe = pd.DataFrame(
        {
            "code": ["600519"],
            "name": ["贵州茅台"],
            "pe": [25.0],
            "pb": [6.0],
            "market_cap": [2.1e12],
        }
    )
    snapshot = {"pe_median": 20.0, "pb_median": 5.0, "net_breaker_count": 1, "net_breaker_ratio": 0.1}

    def fail(*_args, **_kwargs):
        raise RuntimeError("tushare auth failed")

    ups_snap = mock.Mock()
    ups_treas = mock.Mock()
    ups_ind = mock.Mock()
    ups_map = mock.Mock()
    ups_idx = mock.Mock()

    with mock.patch.object(run_once, "fetch_all_a_valuation", mock.Mock(return_value=universe)), mock.patch.object(
        run_once, "compute_snapshot", mock.Mock(return_value=snapshot)
    ), mock.patch.object(
        run_once, "fetch_treasury_10y", mock.Mock(return_value=2.21)
    ), mock.patch.object(
        run_once, "upsert_snapshot", ups_snap
    ), mock.patch.object(
        run_once, "upsert_treasury", ups_treas
    ), mock.patch.object(
        run_once.ts, "pro_api", fail
    ), mock.patch.object(
        run_once, "upsert_industry", ups_ind
    ), mock.patch.object(
        run_once, "upsert_mapping", ups_map
    ), mock.patch.object(
        run_once, "upsert_index", ups_idx
    ):
        run_once.collect_once(conn, config)  # 不应抛异常

    ups_snap.assert_called_once_with(conn, today, snapshot)
    ups_treas.assert_called_once_with(conn, today, 2.21)
    ups_ind.assert_not_called()
    ups_map.assert_not_called()
    ups_idx.assert_not_called()
