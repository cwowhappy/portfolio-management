import datetime as dt
from unittest.mock import MagicMock, patch

from collector.model.task import Collector
from collector.model.run import STATUS_SKIPPED, STATUS_SUCCESS
from collector.scheduler.calendar import TradingCalendar
from collector.scheduler.runner import TaskRunner


def _task(gated=True):
    return Collector("t", "t", [], MagicMock(), None, target_table="x", schedule={},
                     validator=MagicMock(), trading_day_gated=gated)


def test_non_trading_day_skipped():
    cal = TradingCalendar(set())  # 空集合 = 全非交易日
    ex = MagicMock()
    runner = TaskRunner("postgresql://u:p@localhost:5432/db", cal, ex)
    res = runner.run(_task())
    assert res.status == STATUS_SKIPPED
    ex.run.assert_not_called()


def test_force_bypasses_calendar():
    cal = TradingCalendar(set())
    ex = MagicMock()
    ex.run.return_value = MagicMock(status=STATUS_SUCCESS, task_code="t", mode="incremental",
                                   source_used=None, rows_written=0, message=None, error=None)
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

import pytest

from collector.executor.executor import StoreError
from collector.scheduler.runner import backoff_delays


def _gated_off_task(retry_max=3, retry_backoff="exponential"):
    return Collector("t", "t", [], MagicMock(), None, target_table="x", schedule={},
                     trading_day_gated=False, retry_max=retry_max, retry_backoff=retry_backoff)


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
    res = runner.run(_task(), params={"date": "2026-08-27"})
    assert res.status == STATUS_SKIPPED
    ex.run.assert_not_called()


def test_invalid_date_raises_friendly_error():
    runner = TaskRunner("postgresql://u:p@localhost:5432/db", TradingCalendar(set()), MagicMock())
    with pytest.raises(ValueError, match="非法日期参数"):
        runner.run(_task(), params={"date": "2026/08/28"})
