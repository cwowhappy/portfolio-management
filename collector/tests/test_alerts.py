"""P0-2 任务终态告警：通用 webhook + runner 接线。

设计要点（已与用户确认）：
- COLLECTOR_ALERT_WEBHOOK 未配置时告警器为 None，行为与现状完全一致（opt-in）；
- 仅 failed / partial 终态触发；发送失败尽力而为（最多 3 次尝试），不影响任务执行语义。
"""

import json
from contextlib import suppress
from unittest.mock import MagicMock, patch

from collector.executor.executor import AllSourcesFailed
from collector.model.run import STATUS_PARTIAL, STATUS_SUCCESS, RunResult
from collector.model.task import Collector
from collector.scheduler.alerts import WebhookAlerter, alerter_from_env
from collector.scheduler.calendar import TradingCalendar
from collector.scheduler.runner import TaskRunner

# ---------------------------------------------------------------- WebhookAlerter 单元


def _resp(status=200):
    resp = MagicMock()
    resp.status = status
    resp.__enter__ = MagicMock(return_value=resp)
    resp.__exit__ = MagicMock(return_value=False)
    return resp


def test_alerter_send_success_posts_json():
    with patch("collector.scheduler.alerts.urllib.request.urlopen", return_value=_resp()) as up:
        ok = WebhookAlerter("http://hook.example/x").send({"task": "t", "status": "failed"})
    assert ok is True
    (req,), _ = up.call_args
    assert req.full_url == "http://hook.example/x"
    assert req.headers["Content-type"] == "application/json"
    assert json.loads(req.data.decode()) == {"task": "t", "status": "failed"}


def test_alerter_retries_then_gives_up_without_raising():
    with (
        patch("collector.scheduler.alerts.urllib.request.urlopen", side_effect=OSError("boom")) as up,
        patch("collector.scheduler.alerts.time.sleep"),
    ):
        ok = WebhookAlerter("http://hook.example/x", max_attempts=3).send({"task": "t"})
    assert ok is False
    assert up.call_count == 3


def test_alerter_recovers_on_retry():
    with (
        patch(
            "collector.scheduler.alerts.urllib.request.urlopen",
            side_effect=[OSError("first fails"), _resp()],
        ),
        patch("collector.scheduler.alerts.time.sleep"),
    ):
        ok = WebhookAlerter("http://hook.example/x", max_attempts=3).send({"task": "t"})
    assert ok is True


def test_alerter_non_2xx_treated_as_failure():
    with (
        patch("collector.scheduler.alerts.urllib.request.urlopen", side_effect=[_resp(status=500)] * 3),
        patch("collector.scheduler.alerts.time.sleep"),
    ):
        ok = WebhookAlerter("http://hook.example/x", max_attempts=3).send({"task": "t"})
    assert ok is False


def test_alerter_from_env_unset_returns_none():
    assert alerter_from_env({}) is None
    assert alerter_from_env({"COLLECTOR_ALERT_WEBHOOK": "   "}) is None


def test_alerter_from_env_configured():
    alerter = alerter_from_env({"COLLECTOR_ALERT_WEBHOOK": "http://hook.example/x"})
    assert isinstance(alerter, WebhookAlerter)
    assert alerter.url == "http://hook.example/x"


# ---------------------------------------------------------------- runner 接线


def _task(retry_max=0):
    # retry_max=0：告警接线测试不关心重试语义，失败即终态，避免真实 30s 退避睡眠
    return Collector(
        "t",
        "t",
        [],
        MagicMock(),
        None,
        target_table="x",
        schedule={},
        validator=MagicMock(),
        trading_day_gated=False,
        retry_max=retry_max,
    )


def _run(ex, alerter):
    """gated=False + patch psycopg：runner 不触库即可走到 executor 返回路径。"""
    runner = TaskRunner("postgresql://u:p@localhost:5432/db", TradingCalendar(set()), ex, alerter=alerter)
    with patch("collector.scheduler.runner.psycopg") as psycopg:
        conn = MagicMock()
        lock_cur = MagicMock()
        lock_cur.fetchone.return_value = (True,)
        conn.execute.return_value = lock_cur
        psycopg.connect.return_value.__enter__.return_value = conn
        return runner.run(_task())


def test_runner_alerts_on_partial():
    ex = MagicMock()
    ex.run.return_value = RunResult("t", "incremental", STATUS_PARTIAL, message="剔行 3 条")
    alerter = MagicMock()
    res = _run(ex, alerter)
    assert res.status == STATUS_PARTIAL
    alerter.send.assert_called_once()
    payload = alerter.send.call_args[0][0]
    assert payload["task"] == "t"
    assert payload["status"] == STATUS_PARTIAL
    assert payload["mode"] == "incremental"
    assert payload["message"] == "剔行 3 条"


def test_runner_no_alert_on_success():
    ex = MagicMock()
    ex.run.return_value = RunResult("t", "incremental", STATUS_SUCCESS)
    alerter = MagicMock()
    _run(ex, alerter)
    alerter.send.assert_not_called()


def test_runner_alerts_on_failure_after_retries():
    ex = MagicMock()
    ex.run.side_effect = AllSourcesFailed("t")
    alerter = MagicMock()
    runner = TaskRunner("postgresql://u:p@localhost:5432/db", TradingCalendar(set()), ex, retry_max=0, alerter=alerter)
    with patch("collector.scheduler.runner.psycopg") as psycopg:
        conn = MagicMock()
        lock_cur = MagicMock()
        lock_cur.fetchone.return_value = (True,)
        conn.execute.return_value = lock_cur
        psycopg.connect.return_value.__enter__.return_value = conn
        with suppress(AllSourcesFailed):
            runner.run(_task())
    alerter.send.assert_called_once()
    payload = alerter.send.call_args[0][0]
    assert payload["status"] == "failed"
    assert payload["task"] == "t"
    assert payload["error"] == "t"  # str(AllSourcesFailed("t"))——异常名不入 payload，原样保留消息


def test_runner_alerts_on_unexpected_exception():
    ex = MagicMock()
    ex.run.side_effect = ValueError("converter 崩了")
    alerter = MagicMock()
    runner = TaskRunner("postgresql://u:p@localhost:5432/db", TradingCalendar(set()), ex, retry_max=0, alerter=alerter)
    with patch("collector.scheduler.runner.psycopg") as psycopg:
        conn = MagicMock()
        lock_cur = MagicMock()
        lock_cur.fetchone.return_value = (True,)
        conn.execute.return_value = lock_cur
        psycopg.connect.return_value.__enter__.return_value = conn
        with suppress(ValueError):
            runner.run(_task())
    alerter.send.assert_called_once()
    assert alerter.send.call_args[0][0]["error"] == "converter 崩了"


def test_runner_without_alerter_unchanged():
    """未注入 alerter（现状默认）时行为不变——冒烟。"""
    ex = MagicMock()
    ex.run.return_value = RunResult("t", "incremental", STATUS_SUCCESS)
    res = _run(ex, None)
    assert res.status == STATUS_SUCCESS
