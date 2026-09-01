from unittest.mock import MagicMock

from collector.scheduler.jobs import assemble_collector, load_task_defs, seed_tasks
from collector.sources.base import SourceError


def test_assemble_collector_wires_registries():
    src_reg, conv_reg, calc_reg, val_reg = MagicMock(), MagicMock(), MagicMock(), MagicMock()
    src_reg.get.side_effect = lambda spec: f"src:{spec['call']}"
    conv_reg.get.return_value = "conv"
    val_reg.get.return_value = "val"
    row = {
        "task_code": "t",
        "task_name": "T",
        "source_ids": [{"source_id": "a", "type": "akshare", "call": "f"}],
        "converter": "field_mapping",
        "calc": None,
        "validator": [{"check": "min_rows", "value": 5, "level": "hard"}],
        "target_table": "x",
        "schedule": {"type": "cron", "cron": "30 15 * * 1-5"},
        "enabled": True,
        "trading_day_gated": True,
        "retry_max": 3,
        "retry_backoff": "exponential",
    }
    regs = {"source": src_reg, "converter": conv_reg, "calc": calc_reg, "validator": val_reg}
    c = assemble_collector(row, regs)
    assert c.sources == ["src:f"]
    assert c.target_table == "x"


def test_seed_tasks_upserts():
    conn = MagicMock()
    seed_tasks(
        conn,
        [
            {
                "task_code": "t",
                "task_name": "T",
                "source_ids": [],
                "converter": "c",
                "calc": None,
                "validator": None,
                "target_table": "x",
                "schedule": {},
                "enabled": True,
                "trading_day_gated": True,
                "retry_max": 3,
                "retry_backoff": "exponential",
            }
        ],
    )
    conn.cursor.return_value.__enter__.return_value.execute.assert_called()
    conn.commit.assert_called()


def test_assemble_collector_validator_none_ok():
    src_reg, conv_reg, calc_reg, val_reg = MagicMock(), MagicMock(), MagicMock(), MagicMock()
    src_reg.get.return_value = "src"
    conv_reg.get.return_value = "conv"
    # validator 应可选：若被调用则抛错，证明 assemble_collector 不会触碰 validator 注册表
    val_reg.get.side_effect = SourceError("不应调用 validator 注册表")
    row = {
        "task_code": "t",
        "task_name": "T",
        "source_ids": [],
        "converter": "field_mapping",
        "calc": None,
        "validator": None,
        "target_table": "x",
        "schedule": {},
        "enabled": True,
        "trading_day_gated": True,
        "retry_max": 3,
        "retry_backoff": "exponential",
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
    mocker.patch(
        "collector.scheduler.jobs.ak.tool_trade_date_hist_sina",
        return_value=pd.DataFrame({"trade_date": [dt.date(2026, 8, 28), dt.date(2026, 8, 31)]}),
    )
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


# ---------------------------------------------------------------- P2 seed 键校验（L5 前半句）

from pathlib import Path

import pytest

TASKS_DIR = Path(__file__).resolve().parent.parent / "tasks"


def _valid_task_def():
    return {
        "task_code": "t",
        "task_name": "T",
        "source_ids": [],
        "converter": "c",
        "calc": None,
        "validator": None,
        "target_table": "x",
        "schedule": {},
        "enabled": True,
        "trading_day_gated": True,
        "retry_max": 3,
        "retry_backoff": "exponential",
    }


def test_seed_tasks_missing_required_key_fails():
    bad = _valid_task_def()
    del bad["target_table"]
    with pytest.raises(ValueError, match="缺必填键.*target_table"):
        seed_tasks(MagicMock(), [bad])


def test_seed_tasks_unknown_key_fails():
    bad = {**_valid_task_def(), "bogus_key": 1}
    with pytest.raises(ValueError, match="含未知键.*bogus_key"):
        seed_tasks(MagicMock(), [bad])


def test_seed_tasks_real_yaml_defs_pass():
    """真实 8 个任务 YAML 必须通过键校验（封闭契约与存量配置一一对应）。"""
    conn = MagicMock()
    seed_tasks(conn, load_task_defs(str(TASKS_DIR)))
    assert conn.cursor.return_value.__enter__.return_value.execute.call_count == 8


# ---------------------------------------------------------------- S1 调度属性 / S3 异常兜底


def test_task_job_scheduler_attributes():
    """S1：每个 job 显式 coalesce/max_instances/misfire_grace_time，不依赖 APScheduler 默认值。"""
    sch = build_scheduler([_task_row_like()], MagicMock(), calendar_refresher=lambda: None)
    job = sch.get_job("t")
    assert job.coalesce is True
    assert job.max_instances == 1
    assert job.misfire_grace_time == 3600
    cal_job = sch.get_job("refresh_trading_calendar")
    assert cal_job.coalesce is True
    assert cal_job.max_instances == 1
    assert cal_job.misfire_grace_time == 86400


def test_task_job_swallows_runner_exception_and_logs(caplog):
    """S3：runner 抛异常时 job 不外抛，只记失败日志，保住后续调度。"""
    runner = MagicMock()
    runner.run.side_effect = RuntimeError("boom")
    sch = build_scheduler([_task_row_like()], runner)
    job = sch.get_job("t")
    with caplog.at_level(logging.ERROR):
        job.func()  # 不应抛出
    runner.run.assert_called_once()
    assert "调度运行失败" in caplog.text
    assert "boom" in caplog.text
