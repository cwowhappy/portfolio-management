import datetime as dt
import json
from types import SimpleNamespace
from unittest.mock import MagicMock

import pytest

from collector.cli import build_parser, main
from collector.model.run import STATUS_SUCCESS, RunResult
from collector.repositories.runs import RunRepository
from collector.repositories.tasks import TaskRepository

# ---------------------------------------------------------------- parser 保持兼容


def test_backfill_requires_range():
    parser = build_parser()
    with pytest.raises(SystemExit):
        parser.parse_args(["backfill", "t"])  # 缺 --start/--end


def test_run_parses_date_and_force():
    parser = build_parser()
    args = parser.parse_args(["run", "t", "--date", "2026-08-28", "--force"])
    assert args.task_code == "t"
    assert args.date == "2026-08-28"
    assert args.force is True


# ---------------------------------------------------------------- TaskRepository.get


def _task_row():
    return (
        "all_a_valuation",
        "全A估值",
        json.dumps([{"source_id": "a"}]),
        "fc",
        None,
        json.dumps([{"check": "min_rows", "value": 1000, "level": "hard"}]),
        "valuation_snapshot",
        json.dumps({"type": "cron", "cron": "30 15 * * 1-5"}),
        True,
        True,
        None,
        3,
        "exponential",
    )


def test_task_get_returns_parsed_dict():
    conn = MagicMock()
    cur = conn.cursor.return_value.__enter__.return_value
    cur.fetchone.return_value = _task_row()
    row = TaskRepository(conn).get("all_a_valuation")
    assert row["task_code"] == "all_a_valuation"
    assert row["source_ids"] == [{"source_id": "a"}]
    assert row["schedule"]["cron"] == "30 15 * * 1-5"
    # get 不关心 enabled
    assert row["enabled"] is True


def test_task_get_returns_none_when_missing():
    conn = MagicMock()
    cur = conn.cursor.return_value.__enter__.return_value
    cur.fetchone.return_value = None
    assert TaskRepository(conn).get("nope") is None


# ---------------------------------------------------------------- RunRepository.list_runs


def test_run_list_runs_returns_rows_desc():
    conn = MagicMock()
    cur = conn.cursor.return_value.__enter__.return_value
    cur.fetchall.return_value = [
        (dt.datetime(2026, 8, 28, 15, 30), "success", "incremental", "a", 100, 456, None, None),
        (dt.datetime(2026, 8, 27, 15, 30), "failed", "incremental", None, 0, None, "boom", None),
    ]
    runs = RunRepository(conn).list_runs("t", 2)
    assert len(runs) == 2
    assert runs[0]["status"] == "success"
    assert runs[0]["started_at"] == dt.datetime(2026, 8, 28, 15, 30)
    assert runs[0]["source_used"] == "a"
    assert runs[0]["rows_affected"] == 456
    assert runs[1]["status"] == "failed"
    assert runs[1]["error"] is None  # list_runs 不返回 error 列
    assert set(runs[0]) == {
        "started_at",
        "status",
        "mode",
        "source_used",
        "rows_written",
        "rows_affected",
        "message",
        "error",
    }
    # 排序必须是显式 started_at DESC
    sql = cur.execute.call_args.args[0]
    assert "ORDER BY" in sql.upper() and "DESC" in sql.upper()


def test_run_list_runs_empty_when_task_missing():
    conn = MagicMock()
    cur = conn.cursor.return_value.__enter__.return_value
    cur.fetchall.return_value = []
    assert RunRepository(conn).list_runs("nope", 20) == []


# ---------------------------------------------------------------- CLI 命令分发


def _cli_mocks(mocker):
    """打桩 main() 依赖，返回 (psycopg, task_repo, conn, task)。"""
    config = SimpleNamespace(database_url="postgresql://u:p@localhost:5432/db", tushare_token="token")
    mocker.patch("collector.cli.load", return_value=config)
    mocker.patch(
        "collector.cli.build_registries",
        return_value={
            "source": MagicMock(),
            "converter": MagicMock(),
            "calc": MagicMock(),
            "validator": MagicMock(),
        },
    )
    psycopg = mocker.patch("collector.cli.psycopg")
    conn = MagicMock()
    psycopg.connect.return_value.__enter__.return_value = conn

    row = {
        "task_code": "t",
        "task_name": "T",
        "source_ids": [],
        "converter": "fc",
        "calc": None,
        "validator": None,
        "target_table": "x",
        "schedule": {},
        "enabled": True,
        "trading_day_gated": True,
        "retry_max": 3,
        "retry_backoff": "exponential",
    }
    task_repo = MagicMock()
    task_repo.get.return_value = row
    mocker.patch("collector.cli.TaskRepository", return_value=task_repo)

    task = MagicMock(task_code="t")
    mocker.patch("collector.cli.assemble_collector", return_value=task)
    mocker.patch("collector.cli.load_calendar", return_value=MagicMock())
    return psycopg, task_repo, conn, task


def test_run_dispatches_to_task_runner(mocker):
    _, task_repo, _, task = _cli_mocks(mocker)
    runner_inst = MagicMock()
    runner_inst.run.return_value = RunResult("t", "incremental", STATUS_SUCCESS)
    mocker.patch("collector.cli.TaskRunner", return_value=runner_inst)

    main(["run", "t", "--date", "2026-08-28", "--force"])

    task_repo.get.assert_called_once_with("t")
    runner_inst.run.assert_called_once_with(task, params={"date": "2026-08-28"}, force=True)


def test_run_without_date_passes_empty_params(mocker):
    _, task_repo, _, task = _cli_mocks(mocker)
    runner_inst = MagicMock()
    mocker.patch("collector.cli.TaskRunner", return_value=runner_inst)

    main(["run", "t"])

    runner_inst.run.assert_called_once_with(task, params={}, force=False)


def test_backfill_dispatches_to_run_backfill(mocker):
    _, task_repo, _, task = _cli_mocks(mocker)
    runner_inst = MagicMock()
    mocker.patch("collector.cli.TaskRunner", return_value=runner_inst)
    run_backfill = mocker.patch("collector.cli.run_backfill")

    main(["backfill", "t", "--start", "2026-01-01", "--end", "2026-01-31"])

    run_backfill.assert_called_once_with(
        runner_inst, task, "2026-01-01", "2026-01-31", progress=mocker.ANY
    )  # P1-7：CLI 接入分片进度仓储（断言注入存在即可，行为由 test_backfill_sharding 覆盖）


def test_history_calls_run_repository(mocker):
    _, task_repo, _, _ = _cli_mocks(mocker)
    run_repo = MagicMock()
    run_repo.list_runs.return_value = [
        {
            "started_at": dt.datetime(2026, 8, 28, 15, 30),
            "status": "success",
            "mode": "incremental",
            "source_used": "a",
            "rows_written": 100,
            "message": None,
            "error": None,
        },
    ]
    mocker.patch("collector.cli.RunRepository", return_value=run_repo)

    main(["history", "t", "--limit", "5"])

    run_repo.list_runs.assert_called_once_with("t", 5)


def test_missing_task_exits_nonzero(mocker):
    _, task_repo, _, _ = _cli_mocks(mocker)
    task_repo.get.return_value = None
    with pytest.raises(SystemExit) as e:
        main(["run", "nope"])
    assert e.value.code == 1


def test_seed_dry_run_prints_codes(mocker, capsys):
    config = SimpleNamespace(database_url="postgresql://u:p@localhost:5432/db", tushare_token="token")
    mocker.patch("collector.cli.load", return_value=config)
    mocker.patch("collector.cli.build_registries", return_value={})
    psycopg = mocker.patch("collector.cli.psycopg")
    conn = MagicMock()
    psycopg.connect.return_value.__enter__.return_value = conn
    mocker.patch("collector.cli.load_task_defs", return_value=[{"task_code": "a"}, {"task_code": "b"}])
    seed_tasks = mocker.patch("collector.cli.seed_tasks")

    main(["seed", "--dry-run"])

    seed_tasks.assert_not_called()
    out = capsys.readouterr().out
    assert "a" in out and "b" in out


def test_seed_syncs_tasks(mocker, capsys):
    config = SimpleNamespace(database_url="postgresql://u:p@localhost:5432/db", tushare_token="token")
    mocker.patch("collector.cli.load", return_value=config)
    mocker.patch("collector.cli.build_registries", return_value={})
    psycopg = mocker.patch("collector.cli.psycopg")
    conn = MagicMock()
    psycopg.connect.return_value.__enter__.return_value = conn
    defs = [{"task_code": "a"}, {"task_code": "b"}]
    mocker.patch("collector.cli.load_task_defs", return_value=defs)
    seed_tasks = mocker.patch("collector.cli.seed_tasks")

    main(["seed"])

    seed_tasks.assert_called_once_with(conn, defs)
    out = capsys.readouterr().out
    assert "2" in out


# ---------------------------------------------------------------- C3 按命令分级配置（规范）


def _db_only_env(mocker, monkeypatch):
    """真实 config.load 路径 + 无 TUSHARE_TOKEN：验证只读 DB 命令可运行（不 patch load）。"""
    monkeypatch.setattr("collector.config.load_dotenv", lambda: None)
    monkeypatch.setenv("DATABASE_URL", "postgresql://u:p@localhost:5432/db")
    monkeypatch.delenv("TUSHARE_TOKEN", raising=False)
    build_registries = mocker.patch("collector.cli.build_registries")
    psycopg = mocker.patch("collector.cli.psycopg")
    conn = MagicMock()
    psycopg.connect.return_value.__enter__.return_value = conn
    return build_registries, conn


def test_list_without_tushare_token_runs(mocker, monkeypatch, capsys):
    """C3：collector list 只要求 DATABASE_URL——无 TUSHARE_TOKEN 可运行，且不实例化数据源。"""
    build_registries, _ = _db_only_env(mocker, monkeypatch)
    task_repo = MagicMock()
    task_repo.list_all.return_value = [
        {"task_code": "all_a_valuation", "enabled": True, "schedule": {"type": "cron", "cron": "30 15 * * 1-5"}},
    ]
    mocker.patch("collector.cli.TaskRepository", return_value=task_repo)
    run_repo = MagicMock()
    run_repo.latest_run_status.return_value = {"all_a_valuation": (None, None)}
    mocker.patch("collector.cli.RunRepository", return_value=run_repo)

    main(["list"])

    build_registries.assert_not_called()
    assert "all_a_valuation" in capsys.readouterr().out


def test_history_without_tushare_token_runs(mocker, monkeypatch):
    """C3：history 同属只读 DB 命令，无 TUSHARE_TOKEN 可运行。"""
    build_registries, _ = _db_only_env(mocker, monkeypatch)
    task_repo = MagicMock()
    task_repo.get.return_value = {"task_code": "t"}
    mocker.patch("collector.cli.TaskRepository", return_value=task_repo)
    run_repo = MagicMock()
    run_repo.list_runs.return_value = []
    mocker.patch("collector.cli.RunRepository", return_value=run_repo)

    main(["history", "t"])

    build_registries.assert_not_called()
    run_repo.list_runs.assert_called_once_with("t", 20)


def test_seed_without_tushare_token_runs(mocker, monkeypatch, capsys):
    """C3：seed 只写 DB 任务定义、不触数据源，无 TUSHARE_TOKEN 可运行。"""
    build_registries, conn = _db_only_env(mocker, monkeypatch)
    defs = [{"task_code": "a"}, {"task_code": "b"}]
    mocker.patch("collector.cli.load_task_defs", return_value=defs)
    seed_tasks = mocker.patch("collector.cli.seed_tasks")

    main(["seed"])

    build_registries.assert_not_called()
    seed_tasks.assert_called_once_with(conn, defs)
    assert "2" in capsys.readouterr().out


def test_run_without_tushare_token_fails_with_hint(mocker, monkeypatch):
    """C3：采集命令（run/backfill）仍需数据源密钥——fail-fast 报错列出缺失项。"""
    monkeypatch.setattr("collector.config.load_dotenv", lambda: None)
    monkeypatch.setenv("DATABASE_URL", "postgresql://u:p@localhost:5432/db")
    monkeypatch.delenv("TUSHARE_TOKEN", raising=False)
    build_registries = mocker.patch("collector.cli.build_registries")

    with pytest.raises(SystemExit, match="TUSHARE_TOKEN"):
        main(["run", "t"])

    build_registries.assert_not_called()


# ---------------------------------------------------------------- 参数错误友好退出


def test_run_invalid_date_exits_with_usage_error(mocker, capsys):
    _, _, _, task = _cli_mocks(mocker)
    runner_inst = MagicMock()
    runner_inst.run.side_effect = ValueError("非法日期参数 date='2026/08/28'，期望 YYYY-MM-DD")
    mocker.patch("collector.cli.TaskRunner", return_value=runner_inst)

    with pytest.raises(SystemExit) as e:
        main(["run", "t", "--date", "2026/08/28"])
    assert e.value.code == 2
    assert "参数错误" in capsys.readouterr().out


def test_backfill_non_range_source_exits_with_message(mocker, capsys):
    _, _, _, task = _cli_mocks(mocker)
    mocker.patch("collector.cli.TaskRunner", return_value=MagicMock())
    mocker.patch("collector.cli.run_backfill", side_effect=ValueError("源 x 不支持区间回填"))
    with pytest.raises(SystemExit) as e:
        main(["backfill", "t", "--start", "2026-01-01", "--end", "2026-01-31"])
    assert e.value.code == 2
    assert "参数错误" in capsys.readouterr().out


# ---------------------------------------------------------------- list / history 空记录 / 采集失败退出码

from collector.executor.executor import AllSourcesFailed, StoreError


def test_list_default_lists_all_tasks_and_shows_last_run(mocker, capsys):
    _, task_repo, _, _ = _cli_mocks(mocker)
    task_repo.list_all.return_value = [
        {"task_code": "all_a_valuation", "enabled": True, "schedule": {"type": "cron", "cron": "30 15 * * 1-5"}},
        {"task_code": "old_task", "enabled": False, "schedule": {"type": "interval", "days": 7}},
    ]
    run_repo = MagicMock()
    run_repo.latest_run_status.return_value = {
        "all_a_valuation": (dt.datetime(2026, 8, 28, 15, 30), "success"),
        "old_task": (None, None),
    }
    mocker.patch("collector.cli.RunRepository", return_value=run_repo)

    main(["list"])

    out = capsys.readouterr().out
    # 默认列出全部（含停用）
    task_repo.list_all.assert_called_once_with()
    task_repo.list_enabled.assert_not_called()
    assert "all_a_valuation" in out
    assert "old_task" in out
    assert "enabled=False" in out
    assert "success" in out  # 展示上次运行状态
    assert "30 15 * * 1-5" in out


def test_list_enabled_only_calls_list_enabled(mocker, capsys):
    _, task_repo, _, _ = _cli_mocks(mocker)
    task_repo.list_enabled.return_value = [
        {"task_code": "all_a_valuation", "enabled": True, "schedule": {"type": "cron", "cron": "30 15 * * 1-5"}},
    ]
    run_repo = MagicMock()
    run_repo.latest_run_status.return_value = {"all_a_valuation": (None, None)}
    mocker.patch("collector.cli.RunRepository", return_value=run_repo)

    main(["list", "--enabled-only"])

    task_repo.list_enabled.assert_called_once_with()
    task_repo.list_all.assert_not_called()
    out = capsys.readouterr().out
    assert "all_a_valuation" in out
    assert "enabled=True" in out


def test_history_empty_prints_hint(mocker, capsys):
    _, task_repo, _, _ = _cli_mocks(mocker)
    run_repo = MagicMock()
    run_repo.list_runs.return_value = []
    mocker.patch("collector.cli.RunRepository", return_value=run_repo)

    main(["history", "t"])

    assert "无运行记录: t" in capsys.readouterr().out


def test_run_all_sources_failed_exits_1(mocker, capsys):
    _, _, _, task = _cli_mocks(mocker)
    runner_inst = MagicMock()
    runner_inst.run.side_effect = AllSourcesFailed("t")
    mocker.patch("collector.cli.TaskRunner", return_value=runner_inst)

    with pytest.raises(SystemExit) as e:
        main(["run", "t"])
    assert e.value.code == 1
    assert "采集失败" in capsys.readouterr().out


def test_run_store_error_exits_1(mocker, capsys):
    _, _, _, task = _cli_mocks(mocker)
    runner_inst = MagicMock()
    runner_inst.run.side_effect = StoreError("db down")
    mocker.patch("collector.cli.TaskRunner", return_value=runner_inst)

    with pytest.raises(SystemExit) as e:
        main(["run", "t"])
    assert e.value.code == 1
    assert "采集失败" in capsys.readouterr().out


def test_run_missing_token_error_explains_full_assembly(monkeypatch):
    """C6-P3-9（2026-10-15 审查）：run/backfill 强制全部密钥的报错注明语境——
    装配全部数据源，即便目标任务本身不用 tushare（避免用户误判为过度强制）。"""
    import collector.config as config_module

    monkeypatch.setattr(config_module, "load_dotenv", lambda: None)
    monkeypatch.setenv("DATABASE_URL", "postgresql://x")
    monkeypatch.delenv("TUSHARE_TOKEN", raising=False)
    with pytest.raises(SystemExit, match="装配全部数据源") as e:
        main(["run", "all_a_valuation"])
    assert "TUSHARE_TOKEN" in str(e.value)


def test_list_missing_database_url_no_full_assembly_hint(monkeypatch):
    """只读命令的缺配置报错不附带 run/backfill 语境（hint 仅挂采集命令）。"""
    import collector.config as config_module

    monkeypatch.setattr(config_module, "load_dotenv", lambda: None)
    monkeypatch.delenv("DATABASE_URL", raising=False)
    with pytest.raises(SystemExit) as e:
        main(["list"])
    assert "装配全部数据源" not in str(e.value)
