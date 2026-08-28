import datetime as dt
from unittest.mock import MagicMock, patch

from collector.model.task import Collector
from collector.model.run import STATUS_SKIPPED, STATUS_SUCCESS
from collector.scheduler.calendar import TradingCalendar
from collector.scheduler.runner import TaskRunner


def _task(gated=True):
    return Collector("t", "t", [], MagicMock(), None, MagicMock(), "x", {}, trading_day_gated=gated)


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
        conn.try_advisory_lock.return_value = True
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
        conn.try_advisory_lock.return_value = False
        psycopg.connect.return_value.__enter__.return_value = conn
        res = runner.run(_task())
        assert res.status == STATUS_SKIPPED
        ex.run.assert_not_called()
