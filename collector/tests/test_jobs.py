from unittest.mock import MagicMock

from collector.scheduler.jobs import assemble_collector, seed_tasks
from collector.sources.base import SourceError


def test_assemble_collector_wires_registries():
    src_reg, conv_reg, calc_reg, val_reg = MagicMock(), MagicMock(), MagicMock(), MagicMock()
    src_reg.get.side_effect = lambda spec: f"src:{spec['call']}"
    conv_reg.get.return_value = "conv"
    val_reg.get.return_value = "val"
    row = {
        "task_code": "t", "task_name": "T",
        "source_ids": [{"source_id": "a", "type": "akshare", "call": "f"}],
        "converter": "field_mapping", "calc": None,
        "validator": [{"check": "min_rows", "value": 5, "level": "hard"}],
        "target_table": "x", "schedule": {"type": "cron", "cron": "30 15 * * 1-5"},
        "enabled": True, "trading_day_gated": True, "retry_max": 3, "retry_backoff": "exponential",
    }
    regs = {"source": src_reg, "converter": conv_reg, "calc": calc_reg, "validator": val_reg}
    c = assemble_collector(row, regs)
    assert c.sources == ["src:f"]
    assert c.target_table == "x"


def test_seed_tasks_upserts():
    conn = MagicMock()
    seed_tasks(conn, [{"task_code": "t", "task_name": "T", "source_ids": [], "converter": "c",
                       "calc": None, "validator": None, "target_table": "x",
                       "schedule": {}, "enabled": True, "trading_day_gated": True,
                       "retry_max": 3, "retry_backoff": "exponential"}])
    conn.cursor.return_value.__enter__.return_value.execute.assert_called()
    conn.commit.assert_called()


def test_assemble_collector_validator_none_ok():
    src_reg, conv_reg, calc_reg, val_reg = MagicMock(), MagicMock(), MagicMock(), MagicMock()
    src_reg.get.return_value = "src"
    conv_reg.get.return_value = "conv"
    # validator 应可选：若被调用则抛错，证明 assemble_collector 不会触碰 validator 注册表
    val_reg.get.side_effect = SourceError("不应调用 validator 注册表")
    row = {
        "task_code": "t", "task_name": "T",
        "source_ids": [], "converter": "field_mapping", "calc": None,
        "validator": None, "target_table": "x", "schedule": {},
        "enabled": True, "trading_day_gated": True, "retry_max": 3, "retry_backoff": "exponential",
    }
    regs = {"source": src_reg, "converter": conv_reg, "calc": calc_reg, "validator": val_reg}
    c = assemble_collector(row, regs)
    assert c.validator is None
    val_reg.get.assert_not_called()


# ---------------------------------------------------------------- C-P1-2 / C-P1-3 调度与日历

import datetime as dt
import logging

import pandas as pd

from collector.scheduler.jobs import (
    build_scheduler,
    check_calendar_staleness,
    refresh_calendar,
)


def _task_row_like(code="t", days=7):
    t = MagicMock()
    t.task_code = code
    t.schedule = {"type": "interval", "days": days}
    return t


def test_never_succeeded_task_runs_immediately():
    sch = build_scheduler([_task_row_like()], MagicMock(), never_succeeded={"t"})
    job = sch.get_job("t")
    assert job.next_run_time is not None
    assert abs((job.next_run_time.replace(tzinfo=None) - dt.datetime.now()).total_seconds()) < 60


def test_succeeded_task_waits_for_trigger():
    sch = build_scheduler([_task_row_like()], MagicMock(), never_succeeded=set())
    job = sch.get_job("t")
    # interval 触发器首次触发在一个间隔之后，不是启动时刻（pending job 无 next_run_time）
    assert getattr(job, "next_run_time", None) is None


def test_calendar_refresh_job_registered():
    sch = build_scheduler([_task_row_like()], MagicMock(), calendar_refresher=lambda: None)
    job = sch.get_job("refresh_trading_calendar")
    assert job is not None
    assert "day='1'" in str(job.trigger)  # 每月 1 日


def test_refresh_calendar_upserts_even_when_nonempty(mocker):
    """刷新任务幂等：表非空也照常拉取并 ON CONFLICT upsert。"""
    mocker.patch("collector.scheduler.jobs.ak.tool_trade_date_hist_sina",
                 return_value=pd.DataFrame({"trade_date": [dt.date(2026, 8, 28), dt.date(2026, 8, 31)]}))
    conn = MagicMock()
    refresh_calendar(conn)
    cur = conn.cursor.return_value.__enter__.return_value
    cur.executemany.assert_called_once()
    assert "ON CONFLICT DO NOTHING" in cur.executemany.call_args.args[0]
    conn.commit.assert_called_once()


def test_stale_calendar_warns(caplog):
    conn = MagicMock()
    cur = conn.cursor.return_value.__enter__.return_value
    cur.fetchone.return_value = (dt.date(2026, 1, 1),)
    with caplog.at_level(logging.WARNING):
        check_calendar_staleness(conn, today=dt.date(2026, 8, 29))
    assert "交易日历" in caplog.text


def test_fresh_calendar_no_warning(caplog):
    conn = MagicMock()
    cur = conn.cursor.return_value.__enter__.return_value
    cur.fetchone.return_value = (dt.date(2026, 8, 28),)
    with caplog.at_level(logging.WARNING):
        check_calendar_staleness(conn, today=dt.date(2026, 8, 29))
    assert "交易日历" not in caplog.text
