import json
from unittest.mock import MagicMock

from collector.repositories.tasks import TaskRepository


def test_list_all_parses_jsonb_without_enabled_filter():
    conn = MagicMock()
    cur = conn.cursor.return_value.__enter__.return_value
    cur.fetchall.return_value = [
        (
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
            3,
            "exponential",
        ),
        (
            "old_task",
            "停用任务",
            json.dumps([{"source_id": "b"}]),
            "fc",
            None,
            None,
            "x",
            json.dumps({"type": "interval", "days": 7}),
            False,
            False,
            3,
            "exponential",
        ),
    ]
    rows = TaskRepository(conn).list_all()
    assert [r["task_code"] for r in rows] == ["all_a_valuation", "old_task"]
    assert rows[1]["enabled"] is False
    # 不附加 enabled 过滤条件：整表查询，无 WHERE 子句
    sql = cur.execute.call_args.args[0]
    assert "WHERE" not in sql.upper()


def test_list_enabled_parses_jsonb():
    conn = MagicMock()
    cur = conn.cursor.return_value.__enter__.return_value
    cur.fetchall.return_value = [
        (
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
            3,
            "exponential",
        ),
    ]
    rows = TaskRepository(conn).list_enabled()
    assert rows[0]["task_code"] == "all_a_valuation"
    assert rows[0]["source_ids"] == [{"source_id": "a"}]
    assert rows[0]["schedule"]["cron"] == "30 15 * * 1-5"


def test_list_enabled_handles_parsed_jsonb():
    """psycopg3 默认把 JSONB 返回为已解析的 list/dict（非 JSON 字符串），须兼容。"""
    conn = MagicMock()
    cur = conn.cursor.return_value.__enter__.return_value
    cur.fetchall.return_value = [
        (
            "all_a_valuation",
            "全A估值",
            [{"source_id": "a"}],
            "fc",
            None,
            [{"check": "min_rows", "value": 1000, "level": "hard"}],
            "valuation_snapshot",
            {"type": "cron", "cron": "30 15 * * 1-5"},
            True,
            True,
            3,
            "exponential",
        ),
    ]
    rows = TaskRepository(conn).list_enabled()
    assert rows[0]["source_ids"] == [{"source_id": "a"}]
    assert rows[0]["validator"] == [{"check": "min_rows", "value": 1000, "level": "hard"}]
    assert rows[0]["schedule"]["cron"] == "30 15 * * 1-5"


def test_get_parses_null_jsonb_columns_as_none():
    """JSONB 列为 NULL（如无 validator 的任务）时应解析为 None，不报错。"""
    conn = MagicMock()
    cur = conn.cursor.return_value.__enter__.return_value
    cur.fetchone.return_value = (
        "t",
        "T",
        [{"source_id": "a"}],
        "fc",
        None,
        None,  # validator 为 NULL
        "x",
        {"type": "cron", "cron": "0 9 * * *"},
        True,
        True,
        None,
        None,
    )
    row = TaskRepository(conn).get("t")
    assert row["task_code"] == "t"
    assert row["validator"] is None
    assert row["source_ids"] == [{"source_id": "a"}]


def test_upsert_sql_updates_enabled_on_conflict():
    """C-1：ON CONFLICT DO UPDATE 必须含 enabled=EXCLUDED.enabled，否则声明式启停失效。"""
    from collector.repositories.tasks import UPSERT_TASK

    assert "enabled=EXCLUDED.enabled" in UPSERT_TASK


def test_upsert_serializes_jsonb_and_commits():
    conn = MagicMock()
    cur = conn.cursor.return_value.__enter__.return_value
    TaskRepository(conn).upsert(
        {
            "task_code": "all_a_valuation",
            "task_name": "全A估值",
            "source_ids": [{"source_id": "a"}],
            "converter": "fc",
            "calc": None,
            "validator": [{"check": "min_rows", "value": 1000, "level": "hard"}],
            "target_table": "valuation_snapshot",
            "schedule": {"type": "cron", "cron": "30 15 * * 1-5"},
            "enabled": True,
            "trading_day_gated": True,
            "retry_max": 3,
            "retry_backoff": "exponential",
        }
    )
    args = cur.execute.call_args.args[1]
    assert isinstance(args[2], str)  # source_ids 被 json.dumps
    assert isinstance(args[5], str)  # validator 被 json.dumps
    conn.commit.assert_called_once()


def test_health_get_maps_rows_to_source_health():
    from datetime import datetime

    from collector.repositories.health import HealthRepository

    conn = MagicMock()
    cur = conn.cursor.return_value.__enter__.return_value
    cur.fetchall.return_value = [
        ("a", 10, 8, 1, 200, datetime(2026, 8, 28, 10, 0), None, None, 80.0),
    ]
    result = HealthRepository(conn).get(["a"])
    assert result["a"].source_id == "a"
    assert result["a"].total_runs == 10
    assert result["a"].success_runs == 8
    assert result["a"].score == 80.0


def test_health_get_empty_returns_empty_dict():
    from collector.repositories.health import HealthRepository

    assert HealthRepository(MagicMock()).get([]) == {}


def test_health_save_executes_upsert():
    from collector.model.health import SourceHealth
    from collector.repositories.health import HealthRepository

    conn = MagicMock()
    cur = conn.cursor.return_value.__enter__.return_value
    HealthRepository(conn).save(SourceHealth(source_id="a", total_runs=1, success_runs=1, score=90.0))
    cur.execute.assert_called_once()
    conn.commit.assert_called_once()


def test_run_record_returns_run_id():
    from collector.repositories.runs import RunRepository

    conn = MagicMock()
    cur = conn.cursor.return_value.__enter__.return_value
    cur.fetchone.side_effect = [(1,), (42,)]
    run_id = RunRepository(conn).record(
        "all_a_valuation",
        "incremental",
        "success",
        source_used="a",
        params={"day": "20260828"},
        rows_written=100,
    )
    assert run_id == 42
    conn.commit.assert_called_once()


def test_run_record_returns_none_when_task_missing():
    from collector.repositories.runs import RunRepository

    conn = MagicMock()
    cur = conn.cursor.return_value.__enter__.return_value
    cur.fetchone.return_value = None
    assert RunRepository(conn).record("nope", "incremental", "success") is None


# ---------------------------------------------------------------- P2: finished_at / 未知任务告警 / 冷启动查询


def test_run_record_writes_finished_at():
    from collector.repositories.runs import RunRepository

    conn = MagicMock()
    cur = conn.cursor.return_value.__enter__.return_value
    cur.fetchone.side_effect = [(1,), (42,)]
    RunRepository(conn).record("t", "incremental", "success")
    insert_sql = cur.execute.call_args_list[1].args[0]
    assert "finished_at" in insert_sql


def test_run_record_warns_on_unknown_task(caplog):
    import logging

    from collector.repositories.runs import RunRepository

    conn = MagicMock()
    cur = conn.cursor.return_value.__enter__.return_value
    cur.fetchone.return_value = None
    with caplog.at_level(logging.WARNING):
        assert RunRepository(conn).record("nope", "incremental", "success") is None
    assert "未知任务 nope" in caplog.text


def test_start_run_inserts_running_row_and_returns_id():
    from collector.repositories.runs import RunRepository

    conn = MagicMock()
    cur = conn.cursor.return_value.__enter__.return_value
    cur.fetchone.return_value = (77,)  # INSERT..SELECT..RETURNING id -> 77
    run_id = RunRepository(conn).start_run("t", "incremental", {"date": "2026-08-28"})
    assert run_id == 77
    sql = cur.execute.call_args.args[0]
    assert "running" in sql  # 单条 INSERT..SELECT 的 status 为 running
    assert "finished_at" not in sql  # running 行不写 finished_at
    assert "WHERE task_code" in sql  # 未知任务返回空，不插行
    conn.commit.assert_called_once()


def test_start_run_returns_none_when_task_missing():
    from collector.repositories.runs import RunRepository

    conn = MagicMock()
    cur = conn.cursor.return_value.__enter__.return_value
    cur.fetchone.return_value = None
    assert RunRepository(conn).start_run("nope", "incremental", None) is None


def test_finish_run_updates_row_with_finished_at(caplog):
    import logging

    from collector.repositories.runs import RunRepository

    conn = MagicMock()
    cur = conn.cursor.return_value.__enter__.return_value
    RunRepository(conn).finish_run(77, "success", source_used="a", rows_written=5)
    sql = cur.execute.call_args.args[0]
    assert "UPDATE collector_task_run" in sql
    assert "finished_at=now()" in sql
    conn.commit.assert_called_once()
    # run_id 为 None 时 no-op
    with caplog.at_level(logging.DEBUG):
        assert RunRepository(conn).finish_run(None, "success") is None


def test_latest_run_status_returns_mapping():
    import datetime as dt

    from collector.repositories.runs import RunRepository

    conn = MagicMock()
    cur = conn.cursor.return_value.__enter__.return_value
    cur.fetchall.return_value = [
        ("t1", dt.datetime(2026, 8, 28, 15, 30), "success"),
        ("t2", None, None),  # 无运行记录
    ]
    result = RunRepository(conn).latest_run_status()
    assert result["t1"] == (dt.datetime(2026, 8, 28, 15, 30), "success")
    assert result["t2"] == (None, None)


def test_never_succeeded_returns_codes():
    from collector.repositories.runs import RunRepository

    conn = MagicMock()
    cur = conn.cursor.return_value.__enter__.return_value
    cur.fetchall.return_value = [("a",), ("b",)]
    assert RunRepository(conn).never_succeeded(["a", "b", "c"]) == {"a", "b"}
    assert RunRepository(conn).never_succeeded([]) == set()
