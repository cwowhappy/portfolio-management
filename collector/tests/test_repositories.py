import datetime as dt
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
            json.dumps(["tracking_index_close"]),
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
            None,
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
            None,
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
            ["etf_close"],
            3,
            "exponential",
        ),
    ]
    rows = TaskRepository(conn).list_enabled()
    assert rows[0]["source_ids"] == [{"source_id": "a"}]
    assert rows[0]["validator"] == [{"check": "min_rows", "value": 1000, "level": "hard"}]
    assert rows[0]["schedule"]["cron"] == "30 15 * * 1-5"
    assert rows[0]["depends_on"] == ["etf_close"]


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
        None,  # depends_on 为 NULL
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
            "depends_on": ["etf_close"],
            "retry_max": 3,
            "retry_backoff": "exponential",
        }
    )
    args = cur.execute.call_args.args[1]
    assert isinstance(args[2], str)  # source_ids 被 json.dumps
    assert isinstance(args[5], str)  # validator 被 json.dumps
    assert isinstance(args[10], str)  # depends_on 被 json.dumps
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


# ---------------------------------------------------------------- C1 depends_on 前置检查（真实 PG）


def _seed_upstream_tasks(pg_conn, codes):
    """直插上游任务行（collector_task_run 有 task_id FK）。"""
    for code in codes:
        pg_conn.execute(
            "INSERT INTO collector_task (task_code, task_name, source_ids, converter, target_table, schedule)"
            " VALUES (%s, %s, '[]'::jsonb, 'c', 'x', '{}'::jsonb)",
            (code, code),
        )
    pg_conn.commit()


def _insert_run(pg_conn, task_code, status, day, hour=16, minute=0):
    """直插 run 行（绕过 record——须显式控制 started_at 日期）。datetime 钉死上海墙钟
    （UTC+8，与 SUCCEEDED_ON_SQL 的 AT TIME ZONE 'Asia/Shanghai' 同口径），day 即上海
    自然日——不受测试库会话时区影响（CI postgres service 为 UTC）。"""
    shanghai = dt.timezone(dt.timedelta(hours=8))
    pg_conn.execute(
        "INSERT INTO collector_task_run (task_id, mode, status, started_at)"
        " SELECT id, 'incremental', %s, %s FROM collector_task WHERE task_code=%s",
        (status, dt.datetime.combine(day, dt.time(hour, minute)).replace(tzinfo=shanghai), task_code),
    )
    pg_conn.commit()


def test_succeeded_on_includes_success_and_partial_of_the_day(pg_conn):
    from collector.repositories.runs import RunRepository

    _seed_upstream_tasks(pg_conn, ["etf_close", "tracking_index_close"])
    # current_date 仅取一个「近邻日期」；插入与查询两侧都以上海墙钟钉死，会话时区不漂移
    today = pg_conn.execute("SELECT current_date").fetchone()[0]
    _insert_run(pg_conn, "etf_close", "success", today, hour=15)
    _insert_run(pg_conn, "tracking_index_close", "partial", today, hour=15)
    got = RunRepository(pg_conn).succeeded_on(["etf_close", "tracking_index_close"], today)
    assert got == {"etf_close", "tracking_index_close"}


def test_succeeded_on_excludes_failed_running_and_other_days(pg_conn):
    from collector.repositories.runs import RunRepository

    _seed_upstream_tasks(pg_conn, ["a", "b", "c"])
    today = pg_conn.execute("SELECT current_date").fetchone()[0]
    yesterday = today - dt.timedelta(days=1)
    _insert_run(pg_conn, "a", "failed", today)
    _insert_run(pg_conn, "a", "running", today)
    _insert_run(pg_conn, "b", "success", yesterday)  # 他日 success 不算
    _insert_run(pg_conn, "c", "success", today)  # 对照组：当日 success 算
    got = RunRepository(pg_conn).succeeded_on(["a", "b", "c"], today)
    assert got == {"c"}


def test_succeeded_on_empty_codes_returns_empty_set(pg_conn):
    from collector.repositories.runs import RunRepository

    assert RunRepository(pg_conn).succeeded_on([], dt.date.today()) == set()


def test_succeeded_on_pinned_to_shanghai_natural_day_across_midnight(pg_conn):
    """「当日」钉死上海自然日而非会话时区（对抗态）：上海 15 日 00:05（UTC 视角 14 日
    16:05）属 15 日、上海 14 日 23:59（UTC 视角 14 日 15:59）不属。固定历史日期构造
    （SQL 不涉 now()，无时钟竞态）；UTC 会话（CI postgres service）下旧 ::date 口径
    会把 00:05 的 run 误判到昨日——news_night cron 00:00–06:00 任务族将静默差一天。"""
    from collector.repositories.runs import RunRepository

    _seed_upstream_tasks(pg_conn, ["night_owl", "prev_day"])
    _insert_run(pg_conn, "night_owl", "success", dt.date(2026, 1, 15), hour=0, minute=5)
    _insert_run(pg_conn, "prev_day", "success", dt.date(2026, 1, 14), hour=23, minute=59)
    got = RunRepository(pg_conn).succeeded_on(["night_owl", "prev_day"], dt.date(2026, 1, 15))
    assert got == {"night_owl"}


# ---------------------------------------------------------------- C2 悬挂 running reaper（真实 PG）


def _insert_run_state(pg_conn, task_code, status, started_at, finished_at=None, error=None):
    """直插可控制 started_at/finished_at/error 的 run 行（reaper 测试需亚日精度时刻，
    `_insert_run` 只有日粒度 day/hour）。tz-aware datetime 与 TIMESTAMPTZ 列同走绝对时刻，
    不受容器时区影响。"""
    pg_conn.execute(
        "INSERT INTO collector_task_run (task_id, mode, status, started_at, finished_at, error)"
        " SELECT id, 'incremental', %s, %s, %s, %s FROM collector_task WHERE task_code=%s",
        (status, started_at, finished_at, error, task_code),
    )
    pg_conn.commit()


def test_reap_stale_running_only_reaps_old_running_rows(pg_conn):
    """C2：只把 started_at 早于 cutoff 的 running 行置 failed（原错误保留并追加 reaper 文案、
    finished_at 回填）；cutoff 内的新 running 与已终态的 success 行不动，返回 reap 数。"""
    from collector.repositories.runs import RunRepository

    _seed_upstream_tasks(pg_conn, ["t"])
    now = pg_conn.execute("SELECT now()").fetchone()[0]  # 服务器时刻（tz-aware）
    _insert_run_state(pg_conn, "t", "running", now - dt.timedelta(days=2), error="旧错误")  # 悬挂旧行
    _insert_run_state(pg_conn, "t", "running", now)  # 刚启动的 running，不动
    _insert_run_state(pg_conn, "t", "success", now - dt.timedelta(days=2), finished_at=now)  # 已终态，不动
    reaped = RunRepository(pg_conn).reap_stale_running(now - dt.timedelta(days=1))
    assert reaped == 1
    rows = pg_conn.execute("SELECT status, error, finished_at FROM collector_task_run ORDER BY id").fetchall()
    # 旧行：failed + 原错误保留且追加 reaper 文案 + finished_at 回填
    assert rows[0][0] == "failed"
    assert "旧错误" in rows[0][1] and "reaper" in rows[0][1]
    assert rows[0][2] is not None
    assert rows[1][0] == "running" and rows[1][1] is None and rows[1][2] is None  # 新 running 原样
    assert rows[2][0] == "success" and rows[2][1] is None and rows[2][2] == now  # success 终态不动
