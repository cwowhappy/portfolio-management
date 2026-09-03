import datetime as dt
from unittest.mock import MagicMock, patch

import pytest

from collector.model.run import STATUS_SKIPPED, STATUS_SUCCESS
from collector.model.task import Collector
from collector.scheduler.calendar import TradingCalendar
from collector.scheduler.runner import TaskRunner


def _task(gated=True):
    return Collector(
        "t", "t", [], MagicMock(), None, target_table="x", schedule={}, validator=MagicMock(), trading_day_gated=gated
    )


def test_non_trading_day_skipped():
    cal = TradingCalendar(set())  # 空集合 = 全非交易日
    ex = MagicMock()
    runner = TaskRunner("postgresql://u:p@localhost:5432/db", cal, ex)
    with _patched_pg() as pg:
        pg.connect.return_value.__enter__.return_value = MagicMock()
        res = runner.run(_task())
    assert res.status == STATUS_SKIPPED
    ex.run.assert_not_called()


def test_force_bypasses_calendar():
    cal = TradingCalendar(set())
    ex = MagicMock()
    ex.run.return_value = MagicMock(
        status=STATUS_SUCCESS,
        task_code="t",
        mode="incremental",
        source_used=None,
        rows_written=0,
        message=None,
        error=None,
    )
    runner = TaskRunner("postgresql://u:p@localhost:5432/db", cal, ex)
    with patch("collector.scheduler.runner.psycopg") as psycopg:
        conn = MagicMock()
        lock_cur = MagicMock()
        lock_cur.fetchone.return_value = (True,)
        conn.execute.return_value = lock_cur
        psycopg.connect.return_value.__enter__.return_value = conn
        res = runner.run(_task(), force=True)
        assert res.status == STATUS_SUCCESS
        ex.run.assert_called_once()


def test_concurrent_lock_skipped():
    cal = TradingCalendar({dt.date.today()})
    ex = MagicMock()
    runner = TaskRunner("postgresql://u:p@localhost:5432/db", cal, ex)
    with patch("collector.scheduler.runner.psycopg") as psycopg:
        conn = MagicMock()
        lock_cur = MagicMock()
        lock_cur.fetchone.return_value = (False,)
        conn.execute.return_value = lock_cur
        psycopg.connect.return_value.__enter__.return_value = conn
        res = runner.run(_task())
        assert res.status == STATUS_SKIPPED
        ex.run.assert_not_called()


# ---------------------------------------------------------------- C-P0-1 / C-P1-1 重试与连接

from collector.executor.executor import StoreError
from collector.scheduler.runner import backoff_delays


def _gated_off_task(retry_max=3, retry_backoff="exponential"):
    return Collector(
        "t",
        "t",
        [],
        MagicMock(),
        None,
        target_table="x",
        schedule={},
        trading_day_gated=False,
        retry_max=retry_max,
        retry_backoff=retry_backoff,
    )


def _patched_pg():
    return patch("collector.scheduler.runner.psycopg")


def _wire_pg(pg):
    def new_conn(*args, **kwargs):
        conn = MagicMock()
        lock_cur = MagicMock()
        lock_cur.fetchone.return_value = (True,)
        conn.execute.return_value = lock_cur
        ctx = MagicMock()
        ctx.__enter__.return_value = conn
        ctx.__exit__.return_value = False
        return ctx

    pg.connect.side_effect = new_conn


def test_retry_uses_task_retry_max_and_exponential_backoff():
    ex = MagicMock()
    ex.run.side_effect = StoreError("db down")
    runner = TaskRunner("postgresql://u:p@localhost:5432/db", TradingCalendar(set()), ex)
    with _patched_pg() as pg, patch("collector.scheduler.runner.time.sleep") as sleep:
        _wire_pg(pg)
        with pytest.raises(StoreError):
            runner.run(_gated_off_task(retry_max=3, retry_backoff="exponential"))
    assert ex.run.call_count == 4  # 首次 + 3 次重试
    assert pg.connect.call_count == 4  # 每次尝试都是新连接
    assert [c.args[0] for c in sleep.call_args_list] == [30, 60, 120]


def test_retry_max_one_retries_exactly_once():
    ex = MagicMock()
    ex.run.side_effect = StoreError("db down")
    runner = TaskRunner("postgresql://u:p@localhost:5432/db", TradingCalendar(set()), ex)
    with _patched_pg() as pg, patch("collector.scheduler.runner.time.sleep") as sleep:
        _wire_pg(pg)
        with pytest.raises(StoreError):
            runner.run(_gated_off_task(retry_max=1, retry_backoff="fixed"))
    assert ex.run.call_count == 2
    sleep.assert_called_once_with(30)  # fixed 策略固定 30s


def test_fixed_backoff_sequence_constant():
    assert backoff_delays("fixed", 3) == [30, 30, 30]
    assert backoff_delays("exponential", 3) == [30, 60, 120]
    with pytest.raises(ValueError):
        backoff_delays("unknown", 1)


def test_retry_attempts_do_not_double_count_circuit_failures():
    """熔断按任务运行计数：重试时 executor 收到 count_failures=False。"""
    ex = MagicMock()
    ex.run.side_effect = StoreError("db down")
    runner = TaskRunner("postgresql://u:p@localhost:5432/db", TradingCalendar(set()), ex)
    with _patched_pg() as pg, patch("collector.scheduler.runner.time.sleep"):
        _wire_pg(pg)
        with pytest.raises(StoreError):
            runner.run(_gated_off_task(retry_max=1))
    assert ex.run.call_args_list[0].kwargs["count_failures"] is True
    assert ex.run.call_args_list[1].kwargs["count_failures"] is False


def test_task_without_retry_max_falls_back_to_runner_default():
    """task.retry_max 为 None 时回落 runner 构造时的兜底值。"""
    ex = MagicMock()
    ex.run.side_effect = StoreError("db down")
    runner = TaskRunner("postgresql://u:p@localhost:5432/db", TradingCalendar(set()), ex, retry_max=2)
    with _patched_pg() as pg, patch("collector.scheduler.runner.time.sleep") as sleep:
        _wire_pg(pg)
        with pytest.raises(StoreError):
            runner.run(_gated_off_task(retry_max=None))
    assert ex.run.call_count == 3  # 首次 + runner 兜底的 2 次重试
    assert [c.args[0] for c in sleep.call_args_list] == [30, 60]


# ---------------------------------------------------------------- --date 与交易日门控一致化


def test_gating_uses_user_specified_date():
    # 日历里只有 2026-08-28；显式 --date 2026-08-28 即使今天非交易日也应执行
    cal = TradingCalendar({dt.date(2026, 8, 28)})
    ex = MagicMock()
    ex.run.return_value = MagicMock(status=STATUS_SUCCESS)
    runner = TaskRunner("postgresql://u:p@localhost:5432/db", cal, ex)
    with _patched_pg() as pg:
        _wire_pg(pg)
        res = runner.run(_task(), params={"date": "2026-08-28"})
    assert res.status == STATUS_SUCCESS


def test_gating_skips_user_specified_non_trading_date():
    cal = TradingCalendar({dt.date(2026, 8, 28)})
    ex = MagicMock()
    runner = TaskRunner("postgresql://u:p@localhost:5432/db", cal, ex)
    with _patched_pg() as pg:
        pg.connect.return_value.__enter__.return_value = MagicMock()
        res = runner.run(_task(), params={"date": "2026-08-27"})
    assert res.status == STATUS_SKIPPED
    ex.run.assert_not_called()


def test_invalid_date_raises_friendly_error():
    runner = TaskRunner("postgresql://u:p@localhost:5432/db", TradingCalendar(set()), MagicMock())
    with pytest.raises(ValueError, match="非法日期参数"):
        runner.run(_task(), params={"date": "2026/08/28"})


# ---------------------------------------------------------------- C-6 running 态与 skipped 落库


def _pg_ctx(acquired=True):
    conn = MagicMock()
    lock_cur = MagicMock()
    lock_cur.fetchone.return_value = (acquired,)
    conn.execute.return_value = lock_cur
    ctx = MagicMock()
    ctx.__enter__.return_value = conn
    ctx.__exit__.return_value = False
    return ctx


def test_non_trading_day_skip_records_skipped_run():
    """C-6/FR-10：非交易日 skipped 也要落库（此前直接 return 不记录）。"""
    cal = TradingCalendar(set())
    ex = MagicMock()
    runner = TaskRunner("postgresql://u:p@localhost:5432/db", cal, ex)
    with _patched_pg() as pg, patch("collector.scheduler.runner.RunRepository") as R:
        pg.connect.return_value.__enter__.return_value = MagicMock()
        rr = R.return_value
        res = runner.run(_task())
    assert res.status == STATUS_SKIPPED
    ex.run.assert_not_called()
    rr.record.assert_called_once()
    args = rr.record.call_args
    assert args[0][2] == STATUS_SKIPPED
    assert args.kwargs.get("message") == "非交易日"


def test_lock_conflict_skip_records_skipped_run():
    """C-6/FR-10：锁冲突 skipped 也要落库。"""
    cal = TradingCalendar({dt.date.today()})
    ex = MagicMock()
    runner = TaskRunner("postgresql://u:p@localhost:5432/db", cal, ex)
    with _patched_pg() as pg, patch("collector.scheduler.runner.RunRepository") as R:
        pg.connect.side_effect = lambda *a, **k: _pg_ctx(acquired=False)
        rr = R.return_value
        res = runner.run(_task())
    assert res.status == STATUS_SKIPPED
    ex.run.assert_not_called()
    rr.record.assert_called_once()
    assert rr.record.call_args.args[2] == STATUS_SKIPPED
    assert rr.record.call_args.kwargs.get("message") == "上一实例运行中"


def test_success_path_starts_running_row_and_passes_run_id():
    """C-6/FR-10：执行前插 running 前置行，并把 run_id 传给 executor 收尾。"""
    cal = TradingCalendar({dt.date.today()})
    ex = MagicMock()
    ex.run.return_value = MagicMock(status=STATUS_SUCCESS, task_code="t", mode="incremental")
    runner = TaskRunner("postgresql://u:p@localhost:5432/db", cal, ex)
    with _patched_pg() as pg, patch("collector.scheduler.runner.RunRepository") as R:
        pg.connect.side_effect = lambda *a, **k: _pg_ctx(acquired=True)
        rr = R.return_value
        rr.start_run.return_value = 42
        res = runner.run(_task())
    assert res.status == STATUS_SUCCESS
    rr.start_run.assert_called_once()
    assert ex.run.call_args.kwargs["run_id"] == 42


def test_unexpected_exception_finalizes_running_row_as_failed():
    """意外异常（converter 裸抛）逃逸时，running 前置行要兜底置 failed，不留悬挂 running。"""
    cal = TradingCalendar({dt.date.today()})
    ex = MagicMock()
    ex.run.side_effect = ValueError("converter bug")
    runner = TaskRunner("postgresql://u:p@localhost:5432/db", cal, ex)
    with _patched_pg() as pg, patch("collector.scheduler.runner.RunRepository") as R:
        pg.connect.side_effect = lambda *a, **k: _pg_ctx(acquired=True)
        rr = R.return_value
        rr.start_run.return_value = 42
        with pytest.raises(ValueError, match="converter bug"):
            runner.run(_task())
    rr.finish_run.assert_called_once()
    assert rr.finish_run.call_args.args[0] == 42
    assert rr.finish_run.call_args.args[1] == "failed"
