"""P0-3 数据新鲜度巡检：日终对账 + 告警联动。

已确认设计：交易日 17:30 独立 job；8 张规则表——6 张 trading_day 日频对账
（max(trading_day) < 最近已过交易日即滞后）+ etf_basic/index_constituent 的
updated_at 阈值（14/200 天）；shenwan_mapping（无时间列）与 stock_financial
（季频键）不进第一期。告警复用 P0-2 webhook，语义独立。
"""

import datetime as dt
from unittest.mock import MagicMock, patch

from collector.scheduler.jobs import PATROL_CRON, _freshness_patrol_job, build_scheduler
from collector.scheduler.patrol import DAILY_TABLES, UPDATED_AT_TABLES, run_freshness_patrol

TODAY = dt.date(2026, 9, 25)  # 周五，最近已过交易日
PREV = dt.date(2026, 9, 24)


def _seed_calendar(conn, *dates):
    for d in dates:
        conn.execute("INSERT INTO trading_calendar (trade_date) VALUES (%s)", (d,))
    conn.commit()


def _seed_daily(conn, table, day):
    """各日频表一行最小插入（只满足 NOT NULL/UNIQUE）。"""
    rows = {
        "valuation_snapshot": (
            "INSERT INTO valuation_snapshot (trading_day, pe_median, pb_median, net_breaker_count, net_breaker_ratio)"
            " VALUES (%s, 10, 1, 0, 0)"
        ),
        "industry_valuation": (
            "INSERT INTO industry_valuation (trading_day, industry_code, industry_name)"
            " VALUES (%s, '801010', '农林牧渔')"
        ),
        "index_valuation_history": (
            "INSERT INTO index_valuation_history (trading_day, index_code, index_name) VALUES (%s, '000300', '沪深300')"
        ),
        "treasury_yield_curve": "INSERT INTO treasury_yield_curve (trading_day, term, yield) VALUES (%s, '10Y', 2.5)",
        "stock_valuation_daily": (
            "INSERT INTO stock_valuation_daily (trading_day, stock_code, stock_name) VALUES (%s, '600519', '贵州茅台')"
        ),
        "index_close_history": (
            "INSERT INTO index_close_history (trading_day, index_code, index_name, close)"
            " VALUES (%s, '000300', '沪深300', 4000)"
        ),
    }
    conn.execute(rows[table], (day,))
    conn.commit()


def _seed_all_daily(conn, day):
    for t in DAILY_TABLES:
        _seed_daily(conn, t, day)


def _seed_updated_at(conn, table, updated_at):
    if table == "etf_basic":
        conn.execute(
            "INSERT INTO etf_basic (fund_code, fund_name, category, updated_at)"
            " VALUES ('510300', '沪深300ETF', '宽基', %s)",
            (updated_at,),
        )
    else:
        conn.execute(
            "INSERT INTO index_constituent (index_code, stock_code, updated_at) VALUES ('000300', '600519', %s)",
            (updated_at,),
        )
    conn.commit()


# ---------------------------------------------------------------- 巡检核心（真实 PG）


def test_all_fresh_no_findings(pg_conn):
    _seed_calendar(pg_conn, PREV, TODAY)
    _seed_all_daily(pg_conn, TODAY)
    _seed_updated_at(pg_conn, "etf_basic", dt.datetime.now(dt.UTC))
    _seed_updated_at(pg_conn, "index_constituent", dt.datetime.now(dt.UTC))
    assert run_freshness_patrol(pg_conn, today=TODAY) == []


def test_daily_table_lagging_one_day(pg_conn):
    _seed_calendar(pg_conn, PREV, TODAY)
    for t in DAILY_TABLES:
        if t != "treasury_yield_curve":
            _seed_daily(pg_conn, t, TODAY)
    _seed_daily(pg_conn, "treasury_yield_curve", PREV)
    _seed_updated_at(pg_conn, "etf_basic", dt.datetime.now(dt.UTC))
    _seed_updated_at(pg_conn, "index_constituent", dt.datetime.now(dt.UTC))
    findings = run_freshness_patrol(pg_conn, today=TODAY)
    assert len(findings) == 1
    f = findings[0]
    assert f["table"] == "treasury_yield_curve"
    assert f["kind"] == "trading_day"
    assert f["latest"] == PREV.isoformat()
    assert f["expected"] == TODAY.isoformat()


def test_empty_daily_table_reported(pg_conn):
    _seed_calendar(pg_conn, TODAY)
    for t in DAILY_TABLES:
        if t != "valuation_snapshot":
            _seed_daily(pg_conn, t, TODAY)
    _seed_updated_at(pg_conn, "etf_basic", dt.datetime.now(dt.UTC))
    _seed_updated_at(pg_conn, "index_constituent", dt.datetime.now(dt.UTC))
    findings = run_freshness_patrol(pg_conn, today=TODAY)
    assert [f["table"] for f in findings] == ["valuation_snapshot"]
    assert findings[0]["latest"] is None


def test_updated_at_stale_reported(pg_conn):
    _seed_calendar(pg_conn, TODAY)
    _seed_all_daily(pg_conn, TODAY)
    _seed_updated_at(pg_conn, "etf_basic", dt.datetime.now(dt.UTC))
    _seed_updated_at(
        pg_conn,
        "index_constituent",
        dt.datetime.now(dt.UTC) - dt.timedelta(days=UPDATED_AT_TABLES["index_constituent"] + 1),
    )
    findings = run_freshness_patrol(pg_conn, today=TODAY)
    assert [f["table"] for f in findings] == ["index_constituent"]
    assert findings[0]["kind"] == "updated_at"


def test_calendar_empty_reports_calendar_only(pg_conn):
    _seed_all_daily(pg_conn, TODAY)
    findings = run_freshness_patrol(pg_conn, today=TODAY)
    assert len(findings) == 1
    assert findings[0]["kind"] == "calendar"


# ---------------------------------------------------------------- job 门控与注册（单元）


def _job(calendar_ok=True, alerter=None):
    calendar = MagicMock()
    calendar.is_trading_day.return_value = calendar_ok
    # 注意：psycopg 的 patch 作用域在调用 job() 的各测试内（job 闭包运行时才连接）
    return _freshness_patrol_job("postgresql://u:p@localhost/db", alerter, calendar), calendar


def _run_job(job):
    with patch("collector.scheduler.jobs.psycopg"):
        job()


def test_patrol_job_skips_non_trading_day():
    job, calendar = _job(calendar_ok=False)
    with patch("collector.scheduler.jobs.run_freshness_patrol") as patrol:
        _run_job(job)
    patrol.assert_not_called()
    calendar.is_trading_day.assert_called_once()


def test_patrol_job_sends_alert_when_stale():
    alerter = MagicMock()
    job, _ = _job(alerter=alerter)
    with patch(
        "collector.scheduler.jobs.run_freshness_patrol", return_value=[{"table": "valuation_snapshot"}]
    ) as patrol:
        _run_job(job)
    patrol.assert_called_once()
    alerter.send.assert_called_once()
    payload = alerter.send.call_args[0][0]
    assert payload["type"] == "freshness_patrol"
    assert payload["stale"] == [{"table": "valuation_snapshot"}]


def test_patrol_job_silent_when_fresh_or_no_alerter():
    for findings, alerter in (([], MagicMock()), ([{"table": "x"}], None)):
        job, _ = _job(alerter=alerter)
        with patch("collector.scheduler.jobs.run_freshness_patrol", return_value=findings):
            _run_job(job)  # 不应抛错、不应告警
        if alerter is not None:
            alerter.send.assert_not_called()


def test_scheduler_registers_patrol_job():
    with patch("collector.scheduler.jobs.psycopg"):
        runner = MagicMock()
        scheduler = build_scheduler([], runner, patrol_fn=MagicMock())
    jobs = {j.id: j for j in scheduler.get_jobs()}
    assert "freshness_patrol" in jobs
    assert PATROL_CRON == "30 17 * * 1-5"
