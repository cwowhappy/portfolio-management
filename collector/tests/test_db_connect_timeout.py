"""C2（2026-10-15 审查 H1）：psycopg.connect 统一 connect_timeout=10。

psycopg connect_timeout 缺省 0=无限：PG 地址黑洞时 connect 永久挂起，调度器线程池
（APScheduler 默认 10）被永久占坑。只约束 TCP connect，不触碰查询超时。
覆盖三个模块的代表性调用点（cli/runner/jobs，含 jobs 的 conn_factory 与调度 job）。
"""

import datetime as dt
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

from collector.cli import main as cli_main
from collector.config import DB_CONNECT_TIMEOUT_SECONDS, Config
from collector.model.run import RunResult
from collector.scheduler import jobs, runner

_URL = "postgresql://u:p@localhost:5432/db"


def test_connect_timeout_constant_is_10():
    assert DB_CONNECT_TIMEOUT_SECONDS == 10


def test_cli_main_connect_uses_timeout():
    with (
        patch("collector.cli.psycopg") as psycopg,
        patch("collector.cli.load") as load,
    ):
        load.return_value = SimpleNamespace(database_url=_URL, tushare_token="tok")
        psycopg.connect.return_value.__enter__.return_value = MagicMock()
        cli_main(["list"])  # 只读命令同样建连，桩化后空结果直接返回
    psycopg.connect.assert_called_with(_URL, connect_timeout=10)


def test_runner_run_attempt_connect_uses_timeout():
    calendar = MagicMock(is_trading_day=lambda d: True)
    executor = MagicMock()
    executor.run.return_value = RunResult("t", "incremental", "success")
    task = SimpleNamespace(task_code="t", trading_day_gated=False, retry_max=0, retry_backoff="fixed", depends_on=None)
    with patch("collector.scheduler.runner.psycopg") as psycopg:
        conn = psycopg.connect.return_value.__enter__.return_value
        conn.execute.return_value.fetchone.return_value = (True,)  # advisory lock 获取成功
        runner.TaskRunner(_URL, calendar, executor).run(task)
    psycopg.connect.assert_called_with(_URL, connect_timeout=10)


def test_runner_record_skip_connect_uses_timeout():
    task = SimpleNamespace(task_code="t")
    with patch("collector.scheduler.runner.psycopg") as psycopg:
        psycopg.connect.return_value.__enter__.return_value = MagicMock()
        runner.TaskRunner(_URL, MagicMock(), MagicMock())._record_skip(task, "incremental", {}, "非交易日")
    psycopg.connect.assert_called_with(_URL, connect_timeout=10)


def test_jobs_build_registries_conn_factory_uses_timeout():
    config = Config(database_url=_URL, tushare_token="placeholder")
    with patch("collector.scheduler.jobs.psycopg") as psycopg:
        registries = jobs.build_registries(config)
        registries["source"].plugins["etf_tracking_error"].conn_factory()
    psycopg.connect.assert_called_with(_URL, connect_timeout=10)


def test_jobs_refresh_calendar_job_connect_uses_timeout():
    with (
        patch("collector.scheduler.jobs.psycopg") as psycopg,
        patch("collector.scheduler.jobs.ak"),
    ):
        conn = psycopg.connect.return_value.__enter__.return_value
        conn.cursor.return_value.__enter__.return_value.fetchone.return_value = (dt.date.today(),)
        jobs.refresh_calendar_job(_URL)
    psycopg.connect.assert_called_with(_URL, connect_timeout=10)


def test_jobs_patrol_connect_uses_timeout():
    calendar = MagicMock(is_trading_day=lambda d: True)
    with (
        patch("collector.scheduler.jobs.psycopg") as psycopg,
        patch("collector.scheduler.jobs.run_freshness_patrol", return_value=[]),
    ):
        psycopg.connect.return_value.__enter__.return_value = MagicMock()
        jobs._freshness_patrol_job(_URL, None, calendar)()
    psycopg.connect.assert_called_with(_URL, connect_timeout=10)


def test_jobs_reap_stale_running_connect_uses_timeout():
    with patch("collector.scheduler.jobs.psycopg") as psycopg:
        conn = psycopg.connect.return_value.__enter__.return_value
        conn.cursor.return_value.__enter__.return_value.fetchone.return_value = (0,)  # 无悬挂行
        jobs.reap_stale_running_once(_URL, None, cutoff=dt.datetime.now(dt.UTC))
    psycopg.connect.assert_called_with(_URL, connect_timeout=10)


# 注：jobs.main() 体内的两处调用（启动建连 + TradingCalendar conn_factory lambda）无法
# 行为化单测——main 内含 alembic upgrade 且 scheduler.start() 阻塞；二者与上方
# refresh_calendar_job 是同参数形态的同文件调用，由代表用例 + 代码审查覆盖。
