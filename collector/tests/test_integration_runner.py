"""P0 回归（C-P0-1）：DB 写失败路径的事务完整性，跑真实 PostgreSQL。

窄端到端 runner → executor → store：
① upsert 违反约束失败后，failed run 仍能落库（upsert 已 rollback）
② 整任务重试在新连接上执行
③ 后续合法写入不受中止事务影响
"""
import datetime as dt
from unittest.mock import MagicMock, patch

import psycopg
import pytest

from collector.executor.executor import Executor, StoreError
from collector.executor.selector import SourceSelector
from collector.model.run import STATUS_FAILED, STATUS_SUCCESS
from collector.model.task import Collector
from collector.scheduler.calendar import TradingCalendar
from collector.scheduler.runner import TaskRunner
from collector.store.writer import Store


class _DummySource:
    source_id = "dummy"
    supports_range = False

    def fetch(self, params):
        import pandas as pd
        return pd.DataFrame({"x": [1]})


def _task(retry_max=0):
    return Collector("itest", "集成测试", [_DummySource()], MagicMock(), None,
                     target_table="valuation_snapshot", schedule={},
                     trading_day_gated=False, retry_max=retry_max, retry_backoff="fixed")


def _seed_task(conn):
    conn.execute(
        "INSERT INTO collector_task (task_code, task_name, source_ids, converter, target_table, schedule)"
        " VALUES ('itest', '集成测试', '[]', 'fc', 'valuation_snapshot', '{}') ON CONFLICT DO NOTHING"
    )
    conn.commit()


def test_store_failure_rolls_back_and_retry_uses_fresh_connection(pg_url, pg_conn):
    _seed_task(pg_conn)
    # 违反 trading_day NOT NULL 约束的记录 → upsert 必失败
    bad = [{"trading_day": None, "pe_median": 15.0, "pb_median": 1.5,
            "net_breaker_count": 10, "net_breaker_ratio": 0.1}]
    task = _task(retry_max=1)
    task.converter.convert.return_value = bad

    executor = Executor(SourceSelector(), Store())
    runner = TaskRunner(pg_url, TradingCalendar(set()), executor)
    real_connect = psycopg.connect
    with patch("collector.scheduler.runner.time.sleep") as sleep, \
         patch("collector.scheduler.runner.psycopg.connect", wraps=real_connect) as connect_spy:
        with pytest.raises(StoreError):
            runner.run(task)

    # ② retry_max=1 → 首次 + 1 次重试，每次都在新连接上
    assert connect_spy.call_count == 2
    sleep.assert_called_once_with(30)
    # ① 每次尝试的 failed run 都成功落库（若未 rollback，record 会抛 InFailedSqlTransaction）
    rows = pg_conn.execute(
        "SELECT r.status FROM collector_task_run r JOIN collector_task t ON t.id = r.task_id"
        " WHERE t.task_code='itest' ORDER BY r.id"
    ).fetchall()
    assert [r[0] for r in rows] == [STATUS_FAILED, STATUS_FAILED]

    # ③ 后续合法写入不受此前中止事务影响
    good = [{"trading_day": dt.date(2026, 8, 28), "pe_median": 15.0, "pb_median": 1.5,
             "net_breaker_count": 10, "net_breaker_ratio": 0.1}]
    ok_task = _task()
    ok_task.converter.convert.return_value = good
    result = runner.run(ok_task)
    assert result.status == STATUS_SUCCESS
    count = pg_conn.execute(
        "SELECT count(*) FROM valuation_snapshot WHERE trading_day=%s", (dt.date(2026, 8, 28),)
    ).fetchone()[0]
    assert count == 1


def test_upsert_unknown_table_raises_store_error():
    with pytest.raises(StoreError, match="未知目标表"):
        Store().upsert(MagicMock(), "no_such_table", [{"a": 1}])
