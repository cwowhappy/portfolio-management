import datetime as dt
from types import SimpleNamespace
from unittest import mock

import pandas as pd

import collector.run_once as run_once


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
    mapping = pd.DataFrame(
        {
            "code": ["600519", "000858", "999999"],  # 999999 不在 universe 中，用于测试 code 兜底
            "industry_name": ["食品饮料", "食品饮料", "未知行业"],
        }
    )
    snapshot = {
        "pe_median": 20.0,
        "pb_median": 5.0,
        "net_breaker_count": 1,
        "net_breaker_ratio": 0.1,
    }
    industry_rows = [{"industry_name": "食品饮料", "pe": 22.0, "pb": 5.0}]
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

    with mock.patch.object(run_once, "fetch_all_a_valuation", make("fetch_all_a_valuation", universe)), mock.patch.object(
        run_once, "compute_snapshot", make("compute_snapshot", snapshot)
    ), mock.patch.object(
        run_once.ts, "pro_api", make("pro_api", fake_pro)
    ), mock.patch.object(
        run_once, "fetch_shenwan_mapping", make("fetch_shenwan_mapping", mapping)
    ), mock.patch.object(
        run_once, "compute_industry_valuation", make("compute_industry_valuation", industry_rows)
    ), mock.patch.object(
        run_once, "fetch_treasury_10y", make("fetch_treasury_10y", 2.21)
    ), mock.patch.object(
        run_once, "upsert_snapshot", ups_snap
    ), mock.patch.object(
        run_once, "upsert_treasury", ups_treas
    ), mock.patch.object(
        run_once, "upsert_industry", ups_ind
    ), mock.patch.object(
        run_once, "upsert_mapping", ups_map
    ):
        run_once.collect_once(conn, config)

    # 顺序：fetch → compute → pro/mapping → industry → upserts
    assert calls == [
        "fetch_all_a_valuation",
        "compute_snapshot",
        "pro_api",
        "fetch_shenwan_mapping",
        "compute_industry_valuation",
        "fetch_treasury_10y",
        "upsert_snapshot",
        "upsert_treasury",
        "upsert_industry",
        "upsert_mapping",
    ]

    ups_snap.assert_called_once_with(conn, today, snapshot)
    ups_treas.assert_called_once_with(conn, today, 2.21)
    ups_ind.assert_called_once_with(conn, today, industry_rows)

    # 行业映射：stock_name 取 universe join（universe 有 name），缺失则回退 code；
    # industry_code 暂以 industry_name 兜底（Task 3 无独立 industry_code）。
    expected_mapping_rows = [
        ("600519", "贵州茅台", "食品饮料", "食品饮料"),
        ("000858", "五粮液", "食品饮料", "食品饮料"),
        ("999999", "999999", "未知行业", "未知行业"),
    ]
    ups_map.assert_called_once_with(conn, expected_mapping_rows)
