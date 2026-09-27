"""P0-2 任务终态告警：通用 webhook + runner 接线。

设计要点（已与用户确认）：
- COLLECTOR_ALERT_WEBHOOK 未配置时告警器为 None，行为与现状完全一致（opt-in）；
- 仅 failed / partial 终态触发；发送失败尽力而为（最多 3 次尝试），不影响任务执行语义。
"""

import json
import logging
from contextlib import suppress
from unittest.mock import MagicMock, patch

from collector.executor.executor import AllSourcesFailed
from collector.model.run import STATUS_PARTIAL, STATUS_SUCCESS, RunResult
from collector.model.task import Collector
from collector.scheduler.alerts import FeishuAlerter, WebhookAlerter, _feishu_card, _sign, alerter_from_env
from collector.scheduler.calendar import TradingCalendar
from collector.scheduler.runner import TaskRunner

# ---------------------------------------------------------------- WebhookAlerter 单元


def _resp(status=200, body=b'{"code":0,"msg":"success"}'):
    """urlopen 返回的上下文管理器 mock；body 供飞书 body-code 校验钩子 read()。"""
    resp = MagicMock()
    resp.status = status
    resp.read.return_value = body
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


# ---------------------------------------------------------------- alerter_from_env 优先级（FR-A3）


def test_from_env_feishu_takes_priority_over_generic():
    alerter = alerter_from_env(
        {"FEISHU_BOT_WEBHOOK": "http://f.example/hook", "COLLECTOR_ALERT_WEBHOOK": "http://g.example/x"}
    )
    assert isinstance(alerter, FeishuAlerter)
    assert alerter.url == "http://f.example/hook"


def test_from_env_feishu_secret_attached():
    alerter = alerter_from_env({"FEISHU_BOT_WEBHOOK": "http://f.example/hook", "FEISHU_BOT_SECRET": "s"})
    assert isinstance(alerter, FeishuAlerter)
    assert alerter.secret == "s"


def test_from_env_feishu_secret_blank_means_none():
    alerter = alerter_from_env({"FEISHU_BOT_WEBHOOK": "http://f.example/hook", "FEISHU_BOT_SECRET": "   "})
    assert isinstance(alerter, FeishuAlerter)
    assert alerter.secret is None


def test_from_env_feishu_blank_falls_back_to_generic():
    alerter = alerter_from_env({"FEISHU_BOT_WEBHOOK": "  ", "COLLECTOR_ALERT_WEBHOOK": "http://g.example/x"})
    assert type(alerter) is WebhookAlerter  # 精确类型：回退不能命中飞书


# ---------------------------------------------------------------- 飞书卡片构造（FR-A1/A4）


def _card_of(event):
    return _feishu_card(event)["card"]


def test_task_run_failed_card_is_red_with_fields():
    event = {"type": "task_run", "task": "stock_daily", "status": "failed", "mode": "incremental", "error": "全源熔断"}
    payload = _feishu_card(event)
    assert payload["msg_type"] == "interactive"
    card = payload["card"]
    assert card["header"]["template"] == "red"
    body = card["elements"][0]["text"]["content"]
    assert "**任务**：stock_daily" in body
    assert "**状态**：failed" in body
    assert "**模式**：incremental" in body
    assert "**错误**：全源熔断" in body


def test_task_run_partial_card_is_orange():
    card = _card_of({"type": "task_run", "task": "t", "status": "partial", "mode": "full", "message": "剔行 3 条"})
    assert card["header"]["template"] == "orange"
    assert "**消息**：剔行 3 条" in card["elements"][0]["text"]["content"]


def test_task_run_card_clips_long_error():
    event = {"type": "task_run", "task": "t", "status": "failed", "error": "x" * 2000}
    body = _card_of(event)["elements"][0]["text"]["content"]
    # _clip(limit=800) 产出 799 字符 + 省略号（总长 800）
    assert "x" * 799 in body and "x" * 800 not in body
    assert body.endswith("…")


def test_patrol_card_lists_findings_and_caps_at_eight():
    stale = [
        {"kind": "trading_day", "table": f"t{i}", "latest": "2026-09-01", "expected": "2026-09-26"} for i in range(10)
    ]
    card = _card_of({"type": "freshness_patrol", "date": "2026-09-26", "stale": stale})
    assert card["header"]["template"] == "orange"
    body = card["elements"][0]["text"]["content"]
    assert "`t0`" in body and "`t7`" in body and "`t8`" not in body
    assert "共 10 项" in body


# ---------------------------------------------------------------- FeishuAlerter（FR-A1/A2）


def test_sign_is_deterministic_distinct_and_sha256_sized():
    s1 = _sign("1600000000", "secret-a")
    assert s1 == _sign("1600000000", "secret-a")  # 确定性
    assert s1 != _sign("1600000001", "secret-a")  # 时间戳参与
    assert s1 != _sign("1600000000", "secret-b")  # 密钥参与
    import base64

    assert len(base64.b64decode(s1)) == 32  # HmacSHA256 摘要 32 字节


def test_feishu_alerter_posts_card_without_sign_when_no_secret():
    with patch("collector.scheduler.alerts.urllib.request.urlopen", return_value=_resp()) as up:
        ok = FeishuAlerter("http://hook.example/f").send({"type": "task_run", "task": "t", "status": "failed"})
    assert ok is True
    (req,), _ = up.call_args
    body = json.loads(req.data.decode())
    assert body["msg_type"] == "interactive"
    assert body["card"]["header"]["template"] == "red"
    assert "sign" not in body and "timestamp" not in body


def test_feishu_alerter_with_secret_sends_timestamp_and_sign():
    with patch("collector.scheduler.alerts.urllib.request.urlopen", return_value=_resp()) as up:
        ok = FeishuAlerter("http://hook.example/f", secret="s3cr3t").send(
            {"type": "task_run", "task": "t", "status": "partial"}
        )
    assert ok is True
    (req,), _ = up.call_args
    body = json.loads(req.data.decode())
    assert body["timestamp"].isdigit()
    assert body["sign"] == _sign(body["timestamp"], "s3cr3t")


def test_feishu_alerter_failure_swallowed_like_webhook():
    """尽力而为语义（NFR-2）：全部失败仅返回 False，不抛出。"""
    with (
        patch("collector.scheduler.alerts.urllib.request.urlopen", side_effect=OSError("net down")) as up,
        patch("collector.scheduler.alerts.time.sleep"),
    ):
        ok = FeishuAlerter("http://hook.example/f", max_attempts=3).send({"type": "task_run", "task": "t"})
    assert ok is False
    assert up.call_count == 3


# ---------------------------------------------------------------- 飞书 HTTP 200 + body 业务错误码（终审修复 1）


def test_feishu_http_200_nonzero_body_code_fails_after_retries(caplog):
    """飞书永久性错误（19021 签名不匹配等）以 HTTP 200 + body code 表达——不得吞成假成功。"""
    with (
        patch(
            "collector.scheduler.alerts.urllib.request.urlopen",
            return_value=_resp(body=b'{"code":19021,"msg":"sign match fail"}'),
        ) as up,
        patch("collector.scheduler.alerts.time.sleep"),
        caplog.at_level(logging.WARNING, logger="collector.scheduler.alerts"),
    ):
        ok = FeishuAlerter("http://hook.example/f", max_attempts=3).send({"type": "task_run", "task": "t"})
    assert ok is False
    assert up.call_count == 3
    assert "code=19021" in caplog.text and "sign match fail" in caplog.text


def test_feishu_http_200_zero_body_code_is_success():
    """官方契约 code=0 即成功——body 校验不得误伤正常路径。"""
    with (
        patch(
            "collector.scheduler.alerts.urllib.request.urlopen",
            return_value=_resp(body=b'{"code":0,"msg":"success"}'),
        ) as up,
        patch("collector.scheduler.alerts.time.sleep"),
    ):
        ok = FeishuAlerter("http://hook.example/f", max_attempts=3).send({"type": "task_run", "task": "t"})
    assert ok is True
    assert up.call_count == 1


def test_feishu_http_200_non_json_body_fails_after_retries():
    """body 非 JSON 按未知错误处理（OSError 带原文前 200 字符）——流入重试+warning。"""
    with (
        patch(
            "collector.scheduler.alerts.urllib.request.urlopen",
            return_value=_resp(body=b"<html>gateway error</html>"),
        ) as up,
        patch("collector.scheduler.alerts.time.sleep"),
    ):
        ok = FeishuAlerter("http://hook.example/f", max_attempts=3).send({"type": "task_run", "task": "t"})
    assert ok is False
    assert up.call_count == 3


def test_generic_webhook_http_200_any_body_still_success():
    """基类钩子 no-op 回归护栏：通用 webhook 无 body 契约，200 + 任意 body（含非 JSON）即成功。"""
    with patch(
        "collector.scheduler.alerts.urllib.request.urlopen",
        return_value=_resp(body=b"not-json-at-all"),
    ) as up:
        ok = WebhookAlerter("http://hook.example/x", max_attempts=3).send({"task": "t"})
    assert ok is True
    assert up.call_count == 1


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
