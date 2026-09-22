"""行业估值历史重算源：只补缺失日期 + 与 IndustryValuationCalc 同口径（MS-09 设计 §3.3）。"""

import psycopg
import pytest

from collector.calc.snapshot import IndustryValuationCalc
from collector.scheduler.jobs import _field_columns
from collector.sources.plugins import IndustryValuationBackfillSource
from collector.store.writer import Store


def _seed_day(pg_conn, day, rows):
    """rows: [(stock_code, industry_code, industry_name, pe_ttm, pb, total_mv)]"""
    with pg_conn.cursor() as cur:
        for code, ind_code, ind_name, pe, pb, mv in rows:
            cur.execute(
                "INSERT INTO stock_valuation_daily (trading_day, stock_code, stock_name, pe_ttm, pb, total_mv)"
                " VALUES (%s, %s, %s, %s, %s, %s)"
                " ON CONFLICT (trading_day, stock_code) DO NOTHING",
                (day, code, "N" + code, pe, pb, mv),
            )
            cur.execute(
                "INSERT INTO shenwan_industry_mapping (stock_code, stock_name, industry_code, industry_name)"
                " VALUES (%s, %s, %s, %s) ON CONFLICT (stock_code) DO NOTHING",
                (code, "N" + code, ind_code, ind_name),
            )
    pg_conn.commit()


def test_backfill_source_only_missing_dates(pg_url, pg_conn):
    """已有快照日（含 roe/股息率）不被重算触碰；仅产出更早的缺失日。"""
    _seed_day(
        pg_conn,
        "2026-01-01",
        [
            ("600519", "801780", "银行", 10.0, 1.5, 100.0),
            ("601398", "801780", "银行", 20.0, None, 100.0),
        ],
    )
    _seed_day(pg_conn, "2026-01-02", [("600519", "801780", "银行", 11.0, 1.6, 100.0)])
    with pg_conn.cursor() as cur:  # 每日任务已写过 01-02（roe=15 非空）
        cur.execute(
            "INSERT INTO industry_valuation (trading_day, industry_code, industry_name, pe, pb, roe, dividend_yield)"
            " VALUES ('2026-01-02', '801780', '银行', 11.0, 1.6, 15.0, 4.0)"
            " ON CONFLICT (trading_day, industry_code) DO NOTHING"
        )
    pg_conn.commit()

    # 与生产 conn_factory 同语义：每次返回新连接——源内 `with conn:` 退出时 psycopg3 会
    # commit 并关闭连接（psycopg2 只提交不关），把共享 pg_conn 交给源会关掉后续断言用的连接。
    def conn_factory():
        return psycopg.connect(pg_url)

    src = IndustryValuationBackfillSource("industry_valuation_backfill", conn_factory=conn_factory)
    df = src.fetch({})
    assert set(df["trading_day"]) == {"2026-01-01"}  # 只补缺失日，不含已有 01-02

    records = _field_columns()["field_mapping_industry_history"].convert(df)
    Store().upsert(pg_conn, "industry_valuation", records)
    with pg_conn.cursor() as cur:
        cur.execute(
            "SELECT pe, roe, dividend_yield FROM industry_valuation"
            " WHERE trading_day = '2026-01-02' AND industry_code = '801780'"
        )
        assert cur.fetchone() == (11.0, 15.0, 4.0)  # 既有行原值未动
        cur.execute(
            "SELECT pe, roe FROM industry_valuation WHERE trading_day = '2026-01-01' AND industry_code = '801780'"
        )
        pe, roe = cur.fetchone()
        assert pe == pytest.approx(15.0)  # Σ(pe×mv)/Σmv = (10×100+20×100)/200
        assert roe is None  # 历史行 roe 置 NULL（设计口径）

    assert src.fetch({}).empty  # 幂等：重跑无缺失即 0 行


def test_backfill_source_matches_calc(pg_url, pg_conn):
    """SQL 重算与 IndustryValuationCalc 同输入逐值相等（双实现一致性，含 pb 条件加权边界）。"""
    rows = [
        ("600519", "801780", "银行", 10.0, 1.5, 100.0),
        ("601398", "801780", "银行", 20.0, None, 100.0),  # pb 缺失：分子跳过、分母照计（calc 的 pb 分母无条件 Σcap）
        ("000001", "801780", "银行", -5.0, 1.0, 50.0),  # pe<=0：整行剔除（calc 口径 snapshot.py:25-27）
    ]
    _seed_day(pg_conn, "2026-02-02", rows)

    def conn_factory():
        return psycopg.connect(pg_url)

    df = IndustryValuationBackfillSource("iv", conn_factory=conn_factory).fetch({})
    calc_rows = [
        {"industry_code": c, "industry_name": n, "pe": pe, "pb": pb, "market_cap": mv} for _, c, n, pe, pb, mv in rows
    ]
    calc_out = {r["industry_code"]: r for r in IndustryValuationCalc().compute(calc_rows)}
    sql_row = df[df["industry_code"] == "801780"].iloc[0]
    assert sql_row["pe"] == pytest.approx(calc_out["801780"]["pe"], rel=1e-4)
    assert sql_row["pb"] == pytest.approx(calc_out["801780"]["pb"], rel=1e-4)
