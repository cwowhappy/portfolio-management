"""P1-7 backfill 月片分片 + 进度表。

已确认设计：默认按自然月分片、逐片执行逐片记进度（collector_backfill_progress，
Alembic 0003 运维表）；重跑跳过已完成片、失败片重试覆盖；industry_index_close
整区间一片（全历史拉取+客户端裁剪，分片会导致重复全量拉取）。
"快照型任务按日重放"子项已删除（etf_basic/shenwan_mapping 表无时间维度、
stock_financial 已全量重拉，核实无意义）。
"""

import datetime as dt
from unittest.mock import MagicMock

import pytest

from collector.backfill import BackfillSummary, plan_monthly_shards, run_backfill
from collector.model.run import MODE_BACKFILL, STATUS_SUCCESS, RunResult
from collector.model.task import Collector
from collector.sources.constants import BACKFILL_WHOLE_RANGE_TASKS

# ---------------------------------------------------------------- 分片规划


def test_monthly_shards_mid_month_boundaries():
    shards = plan_monthly_shards(dt.date(2026, 1, 15), dt.date(2026, 4, 10))
    assert shards == [
        (dt.date(2026, 1, 15), dt.date(2026, 1, 31)),
        (dt.date(2026, 2, 1), dt.date(2026, 2, 28)),
        (dt.date(2026, 3, 1), dt.date(2026, 3, 31)),
        (dt.date(2026, 4, 1), dt.date(2026, 4, 10)),
    ]


def test_monthly_shards_whole_months():
    shards = plan_monthly_shards(dt.date(2026, 1, 1), dt.date(2026, 3, 31))
    assert len(shards) == 3
    assert shards[1] == (dt.date(2026, 2, 1), dt.date(2026, 2, 28))


def test_monthly_shards_single_day():
    assert plan_monthly_shards(dt.date(2026, 1, 15), dt.date(2026, 1, 15)) == [
        (dt.date(2026, 1, 15), dt.date(2026, 1, 15))
    ]


def test_whole_range_exception_declared():
    assert "industry_index_close" in BACKFILL_WHOLE_RANGE_TASKS


# ---------------------------------------------------------------- run_backfill 分片执行


def _task(code="t"):
    return Collector(code, code, [], MagicMock(), None, MagicMock(), "x", {})


def _runner(ok=True):
    runner = MagicMock()
    if ok:
        runner.run.return_value = RunResult("t", MODE_BACKFILL, STATUS_SUCCESS)
    else:
        runner.run.side_effect = RuntimeError("boom")
    return runner


def test_run_backfill_executes_monthly_shards():
    runner = _runner()
    summary = run_backfill(runner, _task(), "2026-01-15", "2026-03-20")
    assert runner.run.call_count == 3  # 1月(15-31) / 2月 / 3月(1-20)
    first, last = runner.run.call_args_list[0], runner.run.call_args_list[-1]
    assert first.kwargs["params"] == {"start": "20260115", "end": "20260131"}
    assert last.kwargs["params"] == {"start": "20260301", "end": "20260320"}
    assert isinstance(summary, BackfillSummary)
    assert (summary.total, summary.executed, summary.skipped) == (3, 3, 0)


def test_run_backfill_whole_range_task_single_call():
    runner = _runner()
    task = _task("industry_index_close")
    summary = run_backfill(runner, task, "2020-01-01", "2026-08-28")
    runner.run.assert_called_once_with(
        task,
        mode=MODE_BACKFILL,
        params={"start": "20200101", "end": "20260828"},
        force=True,
    )
    assert (summary.total, summary.executed) == (1, 1)


def test_run_backfill_skips_completed_shards():
    runner = _runner()
    progress = MagicMock()
    progress.completed.return_value = {
        (dt.date(2026, 1, 15), dt.date(2026, 1, 31)),
    }
    summary = run_backfill(runner, _task(), "2026-01-15", "2026-02-10", progress=progress)
    assert runner.run.call_count == 1  # 1 月片跳过，只跑 2 月片
    assert (summary.total, summary.executed, summary.skipped) == (2, 1, 1)
    progress.record.assert_called_once_with("t", dt.date(2026, 2, 1), dt.date(2026, 2, 10), "done")


def test_run_backfill_failure_records_and_raises():
    runner = _runner(ok=False)
    progress = MagicMock()
    progress.completed.return_value = set()
    with pytest.raises(RuntimeError, match="boom"):
        run_backfill(runner, _task(), "2026-01-15", "2026-02-10", progress=progress)
    progress.record.assert_called_once()
    assert progress.record.call_args[0][3] == "failed"


# ---------------------------------------------------------------- 进度表（真实 PG）


def test_migration_creates_progress_table(pg_conn):
    col = pg_conn.execute(
        "SELECT data_type FROM information_schema.columns"
        " WHERE table_name='collector_backfill_progress' AND column_name='shard_start'"
    ).fetchone()
    assert col is not None and col[0] == "date"


def test_progress_repo_roundtrip_and_upsert(pg_conn):
    from collector.repositories.backfill import BackfillProgressRepository

    repo = BackfillProgressRepository(pg_conn)
    repo.record("t", dt.date(2026, 1, 15), dt.date(2026, 1, 31), "done")
    repo.record("t", dt.date(2026, 2, 1), dt.date(2026, 2, 28), "failed", error="boom")
    assert repo.completed("t") == {(dt.date(2026, 1, 15), dt.date(2026, 1, 31))}  # 仅 done 计入跳过
    # 重试同一片：upsert 覆盖 failed → done
    repo.record("t", dt.date(2026, 2, 1), dt.date(2026, 2, 28), "done")
    assert repo.completed("t") == {
        (dt.date(2026, 1, 15), dt.date(2026, 1, 31)),
        (dt.date(2026, 2, 1), dt.date(2026, 2, 28)),
    }


def test_cli_wires_progress_repo(pg_conn, monkeypatch):
    """CLI backfill 命令把进度仓储接进 run_backfill（集成冒烟：单日区间一片执行+落进度）。"""
    from collector import cli

    pg_conn.execute(
        "INSERT INTO collector_task (task_code, task_name, source_ids, converter, target_table, schedule)"
        ' VALUES (\'t\', \'t\', \'[{"source_id":"s","type":"plugin","class":"x"}]\'::jsonb,'
        " 'c', 'x', '{}'::jsonb)"
    )
    pg_conn.commit()
    runner = MagicMock()
    runner.run.return_value = RunResult("t", MODE_BACKFILL, STATUS_SUCCESS)
    monkeypatch.setattr(cli, "load", lambda: MagicMock(database_url="unused"))
    registries = {k: MagicMock() for k in ("source", "converter", "calc", "validator")}
    monkeypatch.setattr(cli, "build_registries", lambda cfg: registries)
    monkeypatch.setattr(cli, "TaskRunner", lambda *a, **k: runner)
    fake_psycopg = MagicMock()
    fake_psycopg.connect.return_value.__enter__.return_value = pg_conn
    monkeypatch.setattr(cli, "psycopg", fake_psycopg)
    cli.main(["backfill", "t", "--start", "2026-01-15", "--end", "2026-01-15"])
    row = pg_conn.execute("SELECT count(*) FROM collector_backfill_progress").fetchone()
    assert row[0] == 1
