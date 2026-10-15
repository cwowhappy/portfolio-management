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
    cur = conn.cursor.return_value.__enter__.return_value
    cur.fetchall.return_value = []  # reconcile 查询：无残留任务
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
                "depends_on": None,
                "retry_max": 3,
                "retry_backoff": "exponential",
            }
        ],
    )
    conn.cursor.return_value.__enter__.return_value.execute.assert_called()
    conn.commit.assert_called()


def test_seed_tasks_disables_stale_tasks():
    """seed reconcile：DB 中存在但不在当前 YAML 集合的 task 被停用（enabled=false）。"""
    conn = MagicMock()
    cur = conn.cursor.return_value.__enter__.return_value
    cur.fetchall.return_value = [("t",), ("stale",)]  # all_codes 返回两个，其中 stale 不在 defs
    seed_tasks(conn, [dict(_valid_task_def(), task_code="t")])
    disable_calls = [c for c in cur.execute.call_args_list if "UPDATE collector_task SET enabled=false" in c.args[0]]
    assert len(disable_calls) == 1
    assert disable_calls[0].args[1] == ("stale",)


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
        "depends_on": None,
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


def test_seed_tasks_invalid_retry_backoff_fails():
    """C6：retry_backoff 值域 ∈ {exponential, fixed}，seed 时 fail-fast（此前要到运行时
    backoff_delays 才 ValueError，非法任务已入库被调度）。"""
    bad = {**_valid_task_def(), "retry_backoff": "linear"}
    with pytest.raises(ValueError, match="retry_backoff 非法.*linear"):
        seed_tasks(MagicMock(), [bad])


def test_seed_tasks_accepts_depends_on_key():
    """C1：depends_on 进封闭键集后，声明依赖的任务定义放行。"""
    conn = MagicMock()
    cur = conn.cursor.return_value.__enter__.return_value
    cur.fetchall.return_value = []  # reconcile 查询：无残留任务
    seed_tasks(conn, [{**_valid_task_def(), "depends_on": ["tracking_index_close", "etf_close"]}])
    conn.commit.assert_called()


def test_seed_tasks_depends_on_does_not_open_unknown_keys():
    """C1：登记 depends_on 不放松封闭契约——未登记键仍拒绝。"""
    bad = {**_valid_task_def(), "depends_on": ["etf_close"], "bogus_key": 1}
    with pytest.raises(ValueError, match="含未知键.*bogus_key"):
        seed_tasks(MagicMock(), [bad])


def test_seed_tasks_real_yaml_defs_pass():
    """真实 30 个任务 YAML 必须通过键校验（封闭契约与存量配置一一对应）。"""
    conn = MagicMock()
    cur = conn.cursor.return_value.__enter__.return_value
    cur.fetchall.return_value = []  # reconcile 查询：无残留任务
    seed_tasks(conn, load_task_defs(str(TASKS_DIR)))
    # 30 次 upsert + 1 次 reconcile all_codes 查询（无残留则不额外 disable）
    assert cur.execute.call_count == 31


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


# ---------------------------------------------------------------- once 模式（--once 单轮执行后退出）

from types import SimpleNamespace

from collector.model.run import (
    STATUS_FAILED,
    STATUS_PARTIAL,
    STATUS_SKIPPED,
    STATUS_SUCCESS,
    RunResult,
)
from collector.model.task import Collector
from collector.scheduler.jobs import (
    _run_task_job,
    main,
    parse_args,
    run_once,
    select_tasks,
    topo_sort_tasks,
)


def _collector(code, deps=None):
    return Collector(
        task_code=code,
        task_name=code.upper(),
        sources=[],
        converter=None,
        calc=None,
        target_table="x",
        schedule={"type": "cron", "cron": "30 15 * * 1-5"},
        depends_on=deps,
    )


def test_parse_args_defaults_keep_scheduler_mode():
    """不带 --once：解析结果走调度器长驻路径，无任务过滤。"""
    args = parse_args([])
    assert args.once is False
    assert args.tasks is None


def test_parse_args_once_with_task_filter():
    args = parse_args(["--once", "--tasks", "a,b"])
    assert args.once is True
    assert args.tasks == "a,b"


def test_run_task_job_returns_bool_for_exit_code_counting():
    """once 模式据返回值计退出码：异常 / failed 终态 → False，其余 → True。
    调度器路径忽略返回值，行为不变。"""
    runner = MagicMock()
    runner.run.return_value = RunResult("t", "incremental", STATUS_SUCCESS)
    assert _run_task_job(runner, _collector("t")) is True
    runner.run.return_value = RunResult("t", "incremental", STATUS_FAILED)
    assert _run_task_job(runner, _collector("t")) is False
    runner.run.side_effect = RuntimeError("boom")
    assert _run_task_job(runner, _collector("t")) is False


def test_select_tasks_returns_all_when_no_filter():
    tasks = [_collector("a"), _collector("b")]
    assert select_tasks(tasks, None) == tasks


def test_select_tasks_filters_by_code_and_rejects_unknown():
    tasks = [_collector("a"), _collector("b")]
    # 输出保持入参序（拓扑排序的前置输入，过滤自身不重排）
    assert [t.task_code for t in select_tasks(tasks, "b,a")] == ["a", "b"]
    with pytest.raises(ValueError, match="未知任务.*c.*可选"):
        select_tasks(tasks, "c")


def test_topo_sort_runs_dependencies_before_dependents():
    """菱形以下真实依赖形态：下游必须排在上游之后（once 无调度器重试，
    顺序错了下游因「依赖未满足」被 skip 且当轮无补偿）。"""
    tasks = [
        _collector("etf_tracking_error", deps=["tracking_index_close", "etf_close"]),
        _collector("etf_close"),
        _collector("tracking_index_close"),
    ]
    codes = [t.task_code for t in topo_sort_tasks(tasks)]
    assert codes.index("tracking_index_close") < codes.index("etf_tracking_error")
    assert codes.index("etf_close") < codes.index("etf_tracking_error")


def test_topo_sort_handles_diamond_dependencies():
    """断言拓扑性质而非兄弟次序：a 最先、d 最后、b/c 互不依赖次序随意（实现细节）。"""
    tasks = [
        _collector("d", deps=["b", "c"]),
        _collector("c", deps=["a"]),
        _collector("b", deps=["a"]),
        _collector("a"),
    ]
    codes = [t.task_code for t in topo_sort_tasks(tasks)]
    assert codes[0] == "a"
    assert codes[-1] == "d"
    assert set(codes[1:-1]) == {"b", "c"}


def test_topo_sort_external_dep_ignored_and_cycle_raises():
    """集合外依赖（未 enabled / 未被 --tasks 选中）不参与排序，交由 runner
    运行时 succeeded_on 前置检查兜底；集合内成环则 fail-fast。"""
    assert [t.task_code for t in topo_sort_tasks([_collector("a", deps=["external"])])] == ["a"]
    with pytest.raises(ValueError, match="环"):
        topo_sort_tasks([_collector("x", deps=["y"]), _collector("y", deps=["x"])])


def test_run_once_runs_all_tasks_topo_ordered_and_patrols(mocker):
    """全量任务拓扑序各跑一次；尾部执行新鲜度巡检（调度器模式挂 17:30 cron，
    once 模式每轮收尾补位）。"""
    patrol = mocker.patch("collector.scheduler.jobs._freshness_patrol_job")
    runner = MagicMock()
    runner.run.return_value = RunResult("t", "incremental", STATUS_SUCCESS)
    tasks = [
        _collector("etf_tracking_error", deps=["etf_close"]),
        _collector("etf_close"),
    ]
    code = run_once(tasks, runner, "postgresql://db/x", calendar=MagicMock(), alerter=None)
    assert code == 0
    assert [c.args[0].task_code for c in runner.run.call_args_list] == ["etf_close", "etf_tracking_error"]
    patrol.assert_called_once()


def test_run_once_continues_after_failure_and_returns_nonzero(mocker):
    """单任务异常不中断其余任务（S3 同款兜底），任一失败退出码 1；
    partial 数据已落库、不健康由 P0-2 告警覆盖，不抬退出码。"""
    mocker.patch("collector.scheduler.jobs._freshness_patrol_job")
    runner = MagicMock()
    runner.run.side_effect = [
        RunResult("a", "incremental", STATUS_SUCCESS),
        RuntimeError("boom"),
        RunResult("c", "incremental", STATUS_PARTIAL),
    ]
    tasks = [_collector("a"), _collector("b"), _collector("c")]
    assert run_once(tasks, runner, "postgresql://db/x", calendar=MagicMock(), alerter=None) == 1
    assert runner.run.call_count == 3


def test_run_once_skipped_and_empty_are_zero(mocker):
    mocker.patch("collector.scheduler.jobs._freshness_patrol_job")
    runner = MagicMock()
    runner.run.return_value = RunResult("t", "incremental", STATUS_SKIPPED)
    assert run_once([_collector("t")], runner, "postgresql://db/x", calendar=MagicMock(), alerter=None) == 0
    assert run_once([], runner, "postgresql://db/x", calendar=MagicMock(), alerter=None) == 0


def _patch_main_env(mocker, once):
    """main() 装配链全量打桩，返回 (argv, build_scheduler mock, run_once mock)。"""
    mocker.patch("collector.scheduler.jobs.install_default_http_timeout")
    mocker.patch("collector.scheduler.jobs.command")
    mocker.patch("collector.scheduler.jobs.AlembicConfig")
    mocker.patch(
        "collector.scheduler.jobs.load",
        return_value=SimpleNamespace(database_url="postgresql://db/x", tushare_token="tok"),
    )
    mocker.patch("collector.scheduler.jobs.build_registries", return_value=MagicMock())
    conn = MagicMock()
    mocker.patch("collector.scheduler.jobs.psycopg.connect", return_value=conn)
    # with psycopg.connect(...) as c: 的 c 是 connect(...).__enter__() 的返回值
    inner = conn.__enter__.return_value
    inner.cursor.return_value.__enter__.return_value.fetchone.return_value = (dt.date(2026, 10, 1),)
    mocker.patch("collector.scheduler.jobs.TaskRepository").return_value.list_enabled.return_value = []
    mocker.patch("collector.scheduler.jobs.RunRepository").return_value.never_succeeded.return_value = set()
    mocker.patch("collector.scheduler.jobs.alerter_from_env", return_value=None)
    mocker.patch("collector.scheduler.jobs.reap_stale_running_once")
    mocker.patch("collector.scheduler.jobs.TaskRunner")
    build_scheduler = mocker.patch("collector.scheduler.jobs.build_scheduler")
    run_once_mock = mocker.patch("collector.scheduler.jobs.run_once", return_value=0)
    return (["--once"] if once else []), build_scheduler, run_once_mock


def test_main_default_builds_scheduler_and_blocks(mocker):
    """守护现状：不带 --once 时走 build_scheduler + start 阻塞长驻，不碰 run_once。"""
    argv, build_scheduler, run_once_mock = _patch_main_env(mocker, once=False)
    assert main(argv) is None
    build_scheduler.assert_called_once()
    build_scheduler.return_value.start.assert_called_once()
    run_once_mock.assert_not_called()


def test_main_once_runs_single_round_without_scheduler(mocker):
    """--once：不建调度器，run_once 收到装配好的 runner 与过滤参数，返回退出码。"""
    argv, build_scheduler, run_once_mock = _patch_main_env(mocker, once=True)
    assert main(argv) == 0
    build_scheduler.assert_not_called()
    run_once_mock.assert_called_once()
    call = run_once_mock.call_args
    assert call.args[2] == "postgresql://db/x"
    assert call.kwargs == {"only": None}
