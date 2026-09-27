"""任务终态告警（P0-2）：通用 webhook，opt-in。

COLLECTOR_ALERT_WEBHOOK 配置任意 JSON POST 端点（钉钉/企微/飞书网关均可适配）。
FEISHU_BOT_WEBHOOK 优先构造 FeishuAlerter（卡片+可选签名）。
发送尽力而为：失败仅记日志并小退避重试，绝不影响任务执行语义。
"""

import base64
import hashlib
import hmac
import json
import logging
import os
import time
import urllib.request

logger = logging.getLogger(__name__)

ALERT_WEBHOOK_ENV = "COLLECTOR_ALERT_WEBHOOK"
FEISHU_WEBHOOK_ENV = "FEISHU_BOT_WEBHOOK"
FEISHU_SECRET_ENV = "FEISHU_BOT_SECRET"

_MAX_FIELD_CHARS = 800
_MAX_STALE_ITEMS = 8


class WebhookAlerter:
    """POST JSON 事件到通用 webhook。不可用/未配置时静默，任务侧零感知。"""

    def __init__(self, url, timeout=5, max_attempts=3):
        self.url = url
        self.timeout = timeout
        self.max_attempts = max_attempts

    def send(self, event: dict) -> bool:
        return self._post_json(event)

    def _validate_response(self, resp) -> None:
        """2xx 后的响应体校验钩子。基类 no-op：通用 webhook 无 body 契约，2xx 即成功（NFR-1）。

        子类可覆写以读 body 判定业务错误；抛出异常即流入 _post_json 的重试+warning 路径。
        须在 urlopen 的 with 块内调用（resp.read() 一次性）。
        """

    def _post_json(self, body: dict) -> bool:
        payload = json.dumps(body, ensure_ascii=False, default=str).encode()
        last_error = None
        for attempt in range(1, self.max_attempts + 1):
            try:
                req = urllib.request.Request(
                    self.url, data=payload, headers={"Content-Type": "application/json"}, method="POST"
                )
                with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                    if 200 <= resp.status < 300:
                        self._validate_response(resp)
                        return True
                    raise OSError(f"webhook 返回非 2xx：{resp.status}")
            except Exception as e:  # noqa: BLE001 告警链路吞掉一切，任务语义优先
                last_error = e
                if attempt < self.max_attempts:
                    time.sleep(0.5 * attempt)
        logger.warning("webhook 告警发送失败（已尝试 %d 次，事件=%s）：%s", self.max_attempts, body, last_error)
        return False


def _sign(timestamp: str, secret: str) -> str:
    """飞书自定义机器人官方签名（与 WebhookAlerter 语义解耦，纯函数可直测）。"""
    digest = hmac.new(f"{timestamp}\n{secret}".encode(), b"", hashlib.sha256).digest()
    return base64.b64encode(digest).decode()


class FeishuAlerter(WebhookAlerter):
    """内部事件转飞书群自定义机器人卡片；secret 配置时带官方 timestamp/sign 签名。

    重试/退避/吞错全部复用父类 _post_json——与通用 webhook 同一尽力而为语义（NFR-2）。
    """

    def __init__(self, url, secret=None, timeout=5, max_attempts=3):
        super().__init__(url, timeout=timeout, max_attempts=max_attempts)
        self.secret = secret

    def send(self, event: dict) -> bool:
        payload = _feishu_card(event)
        if self.secret:
            payload["timestamp"] = str(int(time.time()))
            payload["sign"] = _sign(payload["timestamp"], self.secret)
        return self._post_json(payload)

    def _validate_response(self, resp) -> None:
        """飞书以 HTTP 200 + body JSON 的 code 表达业务错误（19021 签名不匹配/19022 IP 白名单/
        19024 关键词缺失/9499 请求体非法等），2xx 不代表送达。

        code 非零或 body 不可解析均抛 OSError（带原文前 200 字符），
        自然流入 _post_json 重试+warning——避免 secret 抄错时静默假成功。
        """
        raw = resp.read()
        try:
            data = json.loads(raw)
            code = data.get("code")
        except (ValueError, AttributeError):  # 非 JSON / 非对象（如网关兜底页）
            raise OSError(f"飞书响应无法解析（原文前 200 字符）：{raw[:200]!r}") from None
        if code != 0:
            raise OSError(f"飞书返回 code={code}：{data.get('msg', '')}")


def alerter_from_env(env=None) -> WebhookAlerter | None:
    """从环境构造告警器；未配置返回 None（runner 拿到 None 即完全走现状路径）。

    优先级：FEISHU_BOT_WEBHOOK（FeishuAlerter，FEISHU_BOT_SECRET 可选签名）
    > COLLECTOR_ALERT_WEBHOOK（通用 JSON POST 逃生通道）。
    """
    env = os.environ if env is None else env
    feishu_url = env.get(FEISHU_WEBHOOK_ENV, "").strip()
    if feishu_url:
        return FeishuAlerter(feishu_url, secret=env.get(FEISHU_SECRET_ENV, "").strip() or None)
    url = env.get(ALERT_WEBHOOK_ENV, "").strip()
    return WebhookAlerter(url) if url else None


# ---------------------------------------------------------------- 飞书卡片构造（FR-A1/A4）
# 纯函数：内部事件 dict → 飞书群自定义机器人 interactive 卡片（官方 msg_type 之一，无 markdown 类型）。


def _clip(value, limit=_MAX_FIELD_CHARS):
    """None 安全 + 超长截断（截断加省略号，保证消息体远低于飞书 20KB 保守线）。"""
    text = str(value) if value is not None else ""
    return text if len(text) <= limit else text[: limit - 1] + "…"


def _card(title: str, template: str, body_md: str) -> dict:
    return {
        "msg_type": "interactive",
        "card": {
            "config": {"wide_screen_mode": True},
            "header": {"title": {"tag": "plain_text", "content": title}, "template": template},
            "elements": [{"tag": "div", "text": {"tag": "lark_md", "content": body_md}}],
        },
    }


def _task_run_card(event: dict) -> dict:
    status = event.get("status") or ""
    lines = [
        f"**任务**：{event.get('task', '')}",
        f"**状态**：{status}",
        f"**模式**：{event.get('mode', '')}",
    ]
    if event.get("error"):
        lines.append(f"**错误**：{_clip(event['error'])}")
    if event.get("message"):
        lines.append(f"**消息**：{_clip(event['message'])}")
    # failed 红、其余（partial）橙；卡片头模板是飞书官方字段
    return _card("⚠️ 采集任务告警", "red" if status == "failed" else "orange", "\n".join(lines))


def _patrol_card(event: dict) -> dict:
    stale = event.get("stale") or []
    lines = [f"**日期**：{event.get('date', '')}", f"**滞留项**：{len(stale)} 项", ""]
    for item in stale[:_MAX_STALE_ITEMS]:
        expect = f" / 期望 {item['expected']}" if item.get("expected") else ""
        lines.append(f"- `{item.get('table')}`（{item.get('kind')}）：最新 {item.get('latest')}{expect}")
    if len(stale) > _MAX_STALE_ITEMS:
        lines.append(f"- ……共 {len(stale)} 项，仅列前 {_MAX_STALE_ITEMS} 项")
    return _card("🕰 数据新鲜度巡检", "orange", "\n".join(lines))


def _feishu_card(event: dict) -> dict:
    """内部事件 → 飞书卡片 payload。task_run 之外的未知类型按任务卡片兜底（字段缺省为空）。"""
    if event.get("type") == "freshness_patrol":
        return _patrol_card(event)
    return _task_run_card(event)
