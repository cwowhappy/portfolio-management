"""C-1 回归（真实 PG）：UPSERT 漏 enabled + seed reconcile。

YAML 改 enabled:false 或删除 YAML 后 re-seed 必须生效：
① ON CONFLICT DO UPDATE 也更新 enabled（此前漏列，声明式启停失效）
② 库中存在但不在当前 YAML 集合的 task 被 reconcile 停用
"""

from collector.scheduler.jobs import seed_tasks


def _task_def(code, enabled=True):
    return {
        "task_code": code,
        "task_name": f"任务 {code}",
        "source_ids": [],
        "converter": "c",
        "calc": None,
        "validator": None,
        "target_table": "valuation_snapshot",
        "schedule": {"type": "cron", "cron": "30 15 * * 1-5"},
        "enabled": enabled,
        "trading_day_gated": True,
        "retry_max": 3,
        "retry_backoff": "exponential",
    }


def test_seed_flips_enabled_to_false_on_conflict(pg_conn):
    """已存在行改 enabled:false 后重跑 seed → DB enabled 翻转为 false。"""
    seed_tasks(pg_conn, [_task_def("t1", enabled=True)])
    seed_tasks(pg_conn, [_task_def("t1", enabled=False)])
    row = pg_conn.execute("SELECT enabled FROM collector_task WHERE task_code='t1'").fetchone()
    assert row is not None
    assert row[0] is False


def test_seed_flips_enabled_to_true_on_conflict(pg_conn):
    """反向翻转（false→true）同样生效。"""
    seed_tasks(pg_conn, [_task_def("t1", enabled=False)])
    seed_tasks(pg_conn, [_task_def("t1", enabled=True)])
    row = pg_conn.execute("SELECT enabled FROM collector_task WHERE task_code='t1'").fetchone()
    assert row[0] is True


def test_seed_disables_task_removed_from_yaml(pg_conn):
    """删除 YAML 后 re-seed：库中残留 task 被 reconcile 停用（enabled=false），仍可回溯但不再调度。"""
    seed_tasks(pg_conn, [_task_def("keep", enabled=True), _task_def("drop", enabled=True)])
    seed_tasks(pg_conn, [_task_def("keep", enabled=True)])
    row = pg_conn.execute("SELECT enabled FROM collector_task WHERE task_code='drop'").fetchone()
    assert row is not None
    assert row[0] is False
    keep = pg_conn.execute("SELECT enabled FROM collector_task WHERE task_code='keep'").fetchone()
    assert keep[0] is True
