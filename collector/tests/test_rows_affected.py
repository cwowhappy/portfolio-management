"""P0-5 rows_written 口径修正：DB→DB 源的实际影响行数。

已确认设计：collector_task_run 加 rows_affected INT NULL（运维表，Alembic 0002，
无跨服务影响）；Source 基类可选属性 last_affected_rows，executor 成功结束时
getattr 读取回填；rows_written（落库记录数）与 rows_affected（源端影响行数）分开。

修正说明：核实代码后仅 EtfTrackingErrorSource 存在失真（UPDATE 在 fetch 内、
恒返空帧）；IndustryValuationBackfillSource 返回真实 DataFrame 走 writer，
rows_written 本就准确，无需设置属性。
"""

from unittest.mock import MagicMock

from collector.executor.executor import Executor
from collector.model.run import STATUS_SUCCESS
from collector.model.task import Collector
from collector.repositories.runs import RunRepository
from collector.scheduler.jobs import seed_tasks
from collector.sources.base import Source


class _EmptyFrameSource(Source):
    """模拟 DB→DB 源：fetch 返回空帧（writer 0 行），实际影响行数挂属性。"""

    def __init__(self, source_id, affected=None):
        self.source_id = source_id
        self.last_affected_rows = affected

    def fetch(self, params):
        import pandas as pd

        return pd.DataFrame(columns=["x"])


def _run_executor(src):
    from unittest.mock import patch

    selector = MagicMock()
    selector.select.return_value = [src]
    store = MagicMock()
    store.upsert.return_value = 0
    converter = MagicMock()
    converter.convert.return_value = []
    executor = Executor(selector, store)
    task = Collector(
        "t",
        "t",
        [src],
        converter,
        None,
        target_table="x",
        schedule={},
        validator=None,
        trading_day_gated=False,
    )
    with patch("collector.executor.executor.RunRepository") as run_repo_cls:
        run_repo = run_repo_cls.return_value
        result = executor.run(task, "incremental", {}, conn=MagicMock(), run_id=99)
    return result, run_repo


def test_executor_passes_last_affected_rows_to_finish_run():
    src = _EmptyFrameSource("db2db", affected=123)
    result, run_repo = _run_executor(src)
    assert result.status == STATUS_SUCCESS
    run_repo.finish_run.assert_called_once()
    assert run_repo.finish_run.call_args.kwargs["rows_affected"] == 123
    assert run_repo.finish_run.call_args.kwargs["rows_written"] == 0


def test_executor_defaults_rows_affected_none():
    src = _EmptyFrameSource("normal", affected=None)
    _, run_repo = _run_executor(src)
    assert run_repo.finish_run.call_args.kwargs["rows_affected"] is None


# ---------------------------------------------------------------- 迁移与持久化（真实 PG）


def test_migration_adds_rows_affected_column(pg_conn):
    col = pg_conn.execute(
        "SELECT data_type FROM information_schema.columns"
        " WHERE table_name='collector_task_run' AND column_name='rows_affected'"
    ).fetchone()
    assert col is not None and col[0] == "integer"


def test_finish_run_persists_rows_affected(pg_conn):
    seed_tasks(
        pg_conn,
        [
            {
                "task_code": "t",
                "task_name": "t",
                "source_ids": [{"source_id": "s", "type": "plugin", "class": "x"}],
                "converter": "c",
                "calc": None,
                "validator": None,
                "target_table": "x",
                "schedule": {},
                "enabled": True,
                "trading_day_gated": False,
                "depends_on": None,
                "retry_max": 3,
                "retry_backoff": "exponential",
            }
        ],
    )
    repo = RunRepository(pg_conn)
    run_id = repo.start_run("t", "incremental")
    repo.finish_run(run_id, STATUS_SUCCESS, source_used="s", rows_written=0, rows_affected=456)
    row = pg_conn.execute(
        "SELECT rows_written, rows_affected FROM collector_task_run WHERE id=%s", (run_id,)
    ).fetchone()
    assert row == (0, 456)


def test_list_runs_includes_rows_affected(pg_conn):
    runs = RunRepository(pg_conn).list_runs("t", limit=5)
    assert runs == [] or "rows_affected" in runs[0]


# ---------------------------------------------------------------- EtfTrackingErrorSource 集成


def test_etf_tracking_error_sets_last_affected_rows(pg_conn):
    from collector.sources.plugins import EtfTrackingErrorSource

    pg_conn.execute(
        "INSERT INTO etf_basic (fund_code, fund_name, category, tracking_index_code)"
        " VALUES ('510300', '沪深300ETF', '宽基', '000300'), ('510500', '中证500ETF', '宽基', '000905')"
    )
    pg_conn.commit()
    src = EtfTrackingErrorSource("etf_tracking_error", conn_factory=lambda: _reconnect(pg_conn))
    frame = src.fetch({})
    assert frame.empty  # 既有契约：fetch 恒空帧，走 writer 0 行路径
    assert src.last_affected_rows == 2  # 参与集 2 只（样本不足置 null 也计入）


class _Reconnect:
    """conn_factory() 需要 with 语义的连接；测试复用同一真实连接。"""

    def __init__(self, conn):
        self.conn = conn

    def __enter__(self):
        return self.conn

    def __exit__(self, *args):
        return False


def _reconnect(conn):
    return _Reconnect(conn)
