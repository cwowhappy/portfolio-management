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
from collector.scheduler.alerts import (
    DedupAlerter,
    FallbackAlerter,
    FeishuAlerter,
    MailAlerter,
    WebhookAlerter,
    _feishu_card,
    _sign,
    alerter_from_env,
)
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
    assert type(alerter) is DedupAlerter  # C6 起统一 DedupAlerter 包装
    assert isinstance(alerter.inner, WebhookAlerter)
    assert alerter.inner.url == "http://hook.example/x"


# ---------------------------------------------------------------- alerter_from_env 优先级（FR-A3）


def test_from_env_feishu_takes_priority_over_generic():
    alerter = alerter_from_env(
        {"FEISHU_BOT_WEBHOOK": "http://f.example/hook", "COLLECTOR_ALERT_WEBHOOK": "http://g.example/x"}
    )
    assert type(alerter) is DedupAlerter
    assert isinstance(alerter.inner, FeishuAlerter)
    assert alerter.inner.url == "http://f.example/hook"


def test_from_env_feishu_secret_attached():
    alerter = alerter_from_env({"FEISHU_BOT_WEBHOOK": "http://f.example/hook", "FEISHU_BOT_SECRET": "s"})
    assert isinstance(alerter.inner, FeishuAlerter)
    assert alerter.inner.secret == "s"


def test_from_env_feishu_secret_blank_means_none():
    alerter = alerter_from_env({"FEISHU_BOT_WEBHOOK": "http://f.example/hook", "FEISHU_BOT_SECRET": "   "})
    assert isinstance(alerter.inner, FeishuAlerter)
    assert alerter.inner.secret is None


def test_from_env_feishu_blank_falls_back_to_generic():
    alerter = alerter_from_env({"FEISHU_BOT_WEBHOOK": "  ", "COLLECTOR_ALERT_WEBHOOK": "http://g.example/x"})
    assert type(alerter) is DedupAlerter
    assert type(alerter.inner) is WebhookAlerter  # 精确类型：回退不能命中飞书


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


# ---------------------------------------------------------------- C3 DedupAlerter（告警风暴抑制）


def _dedup(inner, t0=1000.0):
    """可控时钟的 DedupAlerter：clock 返回 now["t"]，测试推进 now["t"] 模拟时间流逝。"""
    now = {"t": t0}
    return DedupAlerter(inner, window_seconds=1800, clock=lambda: now["t"]), now


def test_dedup_suppresses_same_signature_within_window_and_repasses_after_expiry():
    """同 (type, task, error) 签名 30 分钟窗口内第二次 send 被抑制（返回 True、inner 只透传一次）；
    窗口外（clock 推进超过 window_seconds）同签名再次透传。"""
    inner = MagicMock()
    inner.send.return_value = True
    dedup, now = _dedup(inner)
    event = {"type": "task_run", "task": "stock_daily", "status": "failed", "error": "全源熔断"}
    assert dedup.send(dict(event)) is True
    assert dedup.send(dict(event)) is True  # 抑制不是失败——调用方零感知
    assert inner.send.call_count == 1
    now["t"] += 1801  # 窗口过期
    assert dedup.send(dict(event)) is True
    assert inner.send.call_count == 2


def test_dedup_different_error_or_task_pass_through():
    """签名含 error 与 task：同 task 不同 error、不同 task 同 error 都是新事件。"""
    inner = MagicMock()
    inner.send.return_value = True
    dedup, _ = _dedup(inner)
    dedup.send({"type": "task_run", "task": "t", "status": "failed", "error": "boom-a"})
    dedup.send({"type": "task_run", "task": "t", "status": "failed", "error": "boom-b"})  # 同 task 不同 error
    dedup.send({"type": "task_run", "task": "t2", "status": "failed", "error": "boom-a"})  # 不同 task 同 error
    assert inner.send.call_count == 3


def test_dedup_patrol_signature_is_sorted_table_set():
    """freshness_patrol 按滞留表名集合签名：集合不变（乱序/字段值变化）抑制，多一张表透传。"""
    inner = MagicMock()
    inner.send.return_value = True
    dedup, _ = _dedup(inner)
    base = {"type": "freshness_patrol", "date": "2026-09-26"}
    dedup.send({**base, "stale": [{"table": "etf_basic"}, {"table": "index_constituent"}]})
    dedup.send(
        {**base, "stale": [{"table": "index_constituent", "latest": "2026-09-20"}, {"table": "etf_basic"}]}
    )  # 同表集合乱序 + latest 值变化 → 抑制
    assert inner.send.call_count == 1
    dedup.send(
        {
            **base,
            "stale": [{"table": "etf_basic"}, {"table": "index_constituent"}, {"table": "stock_valuation_daily"}],
        }
    )
    assert inner.send.call_count == 2  # 表集合变化 → 透传


def test_dedup_failed_send_not_recorded_so_next_passes_through():
    """inner.send 失败不记时间戳：下次同签名仍透传（发送失败≠已送达，不能吞重试机会）。"""
    inner = MagicMock()
    inner.send.return_value = False
    dedup, _ = _dedup(inner)
    event = {"type": "task_run", "task": "t", "status": "failed", "error": "boom"}
    assert dedup.send(event) is False
    assert inner.send.call_count == 1
    assert dedup.send(dict(event)) is False  # 未记时间戳 → 不抑制
    assert inner.send.call_count == 2


# ---------------------------------------------------------------- C6 MailAlerter / FallbackAlerter / 组装

MAIL_ENV = {
    "MAIL_SMTP_HOST": "smtp.x",
    "MAIL_SMTP_PORT": "465",
    "MAIL_SMTP_USERNAME": "u",
    "MAIL_SMTP_PASSWORD": "p",
    "MAIL_FROM": "a@x",
    "ALERT_MAIL_TO": "ops@x,ops2@x",
}


def test_mail_alerter_sends_mime_with_both_recipients():
    """SMTP_SSL + login + sendmail：收件人按逗号拆两个，正文含 task 与 error，Subject 带类型定位。
    构造参数必须带 timeout=10：send 会被 runner 在持 advisory lock 块内同步调用，无超时挂起会拖死任务锁。"""
    import email

    with patch("collector.scheduler.alerts.smtplib.SMTP_SSL") as smtp_ssl:
        smtp = smtp_ssl.return_value.__enter__.return_value
        ok = MailAlerter.from_env(dict(MAIL_ENV)).send(
            {"type": "task_run", "task": "stock_daily", "status": "failed", "error": "全源熔断"}
        )
    assert ok is True
    smtp_ssl.assert_called_once_with("smtp.x", 465, timeout=10)
    smtp.login.assert_called_once_with("u", "p")
    (sender, tos, body), _ = smtp.sendmail.call_args
    assert sender == "a@x"
    assert list(tos) == ["ops@x", "ops2@x"]
    msg = email.message_from_string(body)  # utf-8 正文被 base64 编码，按 MIME 结构断言
    assert msg["Subject"] == "[collector] task_run stock_daily"
    assert msg["From"] == "a@x" and msg["To"] == "ops@x, ops2@x"
    payload = json.loads(msg.get_payload(decode=True).decode("utf-8"))
    assert payload["task"] == "stock_daily" and payload["error"] == "全源熔断"


def test_mail_alerter_smtp_failure_returns_false_without_raising(caplog):
    """SMTP 全程异常吞掉：返回 False 不抛（告警链路绝不影响任务语义），记 ERROR 留痕。"""
    with (
        patch("collector.scheduler.alerts.smtplib.SMTP_SSL", side_effect=OSError("smtp down")),
        caplog.at_level(logging.ERROR, logger="collector.scheduler.alerts"),
    ):
        ok = MailAlerter.from_env(dict(MAIL_ENV)).send({"type": "task_run", "task": "t", "error": "boom"})
    assert ok is False
    assert "smtp down" in caplog.text


def test_fallback_primary_failed_delegates_to_fallback():
    primary, fallback = MagicMock(), MagicMock()
    primary.send.return_value = False
    fallback.send.return_value = True
    assert FallbackAlerter(primary, fallback).send({"type": "task_run"}) is True
    fallback.send.assert_called_once_with({"type": "task_run"})


def test_fallback_primary_ok_skips_fallback():
    primary, fallback = MagicMock(), MagicMock()
    primary.send.return_value = True
    assert FallbackAlerter(primary, fallback).send({"type": "task_run"}) is True
    fallback.send.assert_not_called()


def test_fallback_without_fallback_returns_false():
    primary = MagicMock()
    primary.send.return_value = False
    assert FallbackAlerter(primary, None).send({"type": "task_run"}) is False


def test_assembly_feishu_plus_mail_full_stack():
    """飞书+邮件齐 → DedupAlerter(FallbackAlerter(FeishuAlerter, MailAlerter))，逐层解包断言。"""
    alerter = alerter_from_env({"FEISHU_BOT_WEBHOOK": "http://f.example/h", **MAIL_ENV})
    assert type(alerter) is DedupAlerter
    assert type(alerter.inner) is FallbackAlerter
    assert type(alerter.inner.primary) is FeishuAlerter
    assert type(alerter.inner.fallback) is MailAlerter


def test_assembly_feishu_only_no_fallback_wrapper():
    """仅飞书 → DedupAlerter(FeishuAlerter)，不包 Fallback（无降级通道可包）。"""
    alerter = alerter_from_env({"FEISHU_BOT_WEBHOOK": "http://f.example/h"})
    assert type(alerter) is DedupAlerter
    assert type(alerter.inner) is FeishuAlerter


def test_assembly_all_unset_returns_none():
    assert alerter_from_env({}) is None
    assert alerter_from_env({**{k: "" for k in MAIL_ENV}}) is None


def test_assembly_smtp_without_mail_to_skips_mail_with_warning(caplog):
    """缺 ALERT_MAIL_TO 但 SMTP 四要素齐 → 不构建邮件通道（主通道不包 Fallback）+ WARN 留痕。"""
    env = {"FEISHU_BOT_WEBHOOK": "http://f.example/h", **{k: v for k, v in MAIL_ENV.items() if k != "ALERT_MAIL_TO"}}
    with caplog.at_level(logging.WARNING, logger="collector.scheduler.alerts"):
        alerter = alerter_from_env(env)
    assert type(alerter) is DedupAlerter
    assert type(alerter.inner) is FeishuAlerter  # 未包 FallbackAlerter
    assert "ALERT_MAIL_TO" in caplog.text


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


# ---------------------------------------------------------------- C2 悬挂 running reaper 接线

import datetime as dt

from collector.scheduler.jobs import main, reap_stale_running_once


def _reap_once(alerter, reaped, cutoff):
    """mock jobs.psycopg / jobs.RunRepository 后调 reaper 入口，返回 (返回值, repo mock)。"""
    with (
        patch("collector.scheduler.jobs.psycopg"),
        patch("collector.scheduler.jobs.RunRepository") as repo_cls,
    ):
        repo_cls.return_value.reap_stale_running.return_value = reaped
        got = reap_stale_running_once("postgresql://u:p@localhost/db", alerter, cutoff)
    return got, repo_cls.return_value


def test_reaper_once_alerts_when_stale_rows_reaped():
    alerter = MagicMock()
    cutoff = dt.datetime.now(dt.UTC) - dt.timedelta(days=1)
    got, repo = _reap_once(alerter, 3, cutoff)
    assert got == 3
    repo.reap_stale_running.assert_called_once_with(cutoff)
    alerter.send.assert_called_once()
    payload = alerter.send.call_args[0][0]
    assert payload["type"] == "reaper"
    assert payload["reaped"] == 3
    # message 让飞书 task_run 卡片有文案（type=reaper 落兜底渲染，无 message 卡片全空）
    assert payload["message"] == "reaper: 清理悬挂 running 3 行"


def test_reaper_once_silent_when_nothing_reaped():
    alerter = MagicMock()
    got, _ = _reap_once(alerter, 0, dt.datetime.now(dt.UTC))
    assert got == 0
    alerter.send.assert_not_called()


def test_reaper_once_without_alerter_still_logs(caplog):
    """alerter 未配置（None，webhook 未设是常态）时 warning 照打、不 send、不拖垮启动。"""
    import logging

    with caplog.at_level(logging.WARNING, logger="collector.scheduler.jobs"):
        got, _ = _reap_once(None, 2, dt.datetime.now(dt.UTC))
    assert got == 2
    assert "2" in caplog.text and "reaper" in caplog.text


def test_main_wires_startup_reaper_before_scheduler():
    """main() 接线：reaper 在 alerter 就绪后、调度器构建/启动前执行一次，cutoff 一天前。"""
    alerter = MagicMock()
    order = []
    alerter.send.side_effect = lambda *a, **k: order.append("alert")
    with (
        patch("collector.scheduler.jobs.load") as load,
        patch("collector.scheduler.jobs.AlembicConfig"),
        patch("collector.scheduler.jobs.command"),
        patch("collector.scheduler.jobs.build_registries"),
        patch("collector.scheduler.jobs.psycopg"),
        patch("collector.scheduler.jobs.refresh_calendar"),
        patch("collector.scheduler.jobs.check_calendar_staleness"),
        patch("collector.scheduler.jobs.load_task_defs", return_value=[]),
        patch("collector.scheduler.jobs.seed_tasks"),
        patch("collector.scheduler.jobs.TaskRepository") as task_repo_cls,
        patch("collector.scheduler.jobs.RunRepository") as run_repo_cls,
        patch("collector.scheduler.jobs.alerter_from_env", return_value=alerter),
        patch("collector.scheduler.jobs.build_scheduler") as build_scheduler,
    ):
        load.return_value = MagicMock(database_url="postgresql://u:p@localhost/db")
        task_repo_cls.return_value.list_enabled.return_value = []
        run_repo_cls.return_value.never_succeeded.return_value = set()
        run_repo_cls.return_value.reap_stale_running.return_value = 2
        sched = MagicMock()

        def _record_scheduler(*a, **k):
            order.append("scheduler")
            return sched  # side_effect 非 DEFAULT 时返回值取代 return_value，须回传 sched

        build_scheduler.side_effect = _record_scheduler
        main()
    (cutoff,), _ = run_repo_cls.return_value.reap_stale_running.call_args
    age = dt.datetime.now(dt.UTC) - cutoff
    assert dt.timedelta(hours=23) < age < dt.timedelta(hours=25)  # cutoff 一天
    assert order == ["alert", "scheduler"]  # reaper 告警先于调度器构建（alerter 就绪后、start 前）
    assert alerter.send.call_args[0][0] == {"type": "reaper", "reaped": 2, "message": "reaper: 清理悬挂 running 2 行"}
    sched.start.assert_called_once()
