"""任务终态告警（P0-2）：通用 webhook，opt-in。

COLLECTOR_ALERT_WEBHOOK 配置任意 JSON POST 端点（钉钉/企微/飞书网关均可适配）。
发送尽力而为：失败仅记日志并小退避重试，绝不影响任务执行语义。
"""

import json
import logging
import os
import time
import urllib.request

logger = logging.getLogger(__name__)

ALERT_WEBHOOK_ENV = "COLLECTOR_ALERT_WEBHOOK"


class WebhookAlerter:
    """POST JSON 事件到通用 webhook。不可用/未配置时静默，任务侧零感知。"""

    def __init__(self, url, timeout=5, max_attempts=3):
        self.url = url
        self.timeout = timeout
        self.max_attempts = max_attempts

    def send(self, event: dict) -> bool:
        payload = json.dumps(event, ensure_ascii=False, default=str).encode()
        last_error = None
        for attempt in range(1, self.max_attempts + 1):
            try:
                req = urllib.request.Request(
                    self.url, data=payload, headers={"Content-Type": "application/json"}, method="POST"
                )
                with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                    if 200 <= resp.status < 300:
                        return True
                    raise OSError(f"webhook 返回非 2xx：{resp.status}")
            except Exception as e:  # noqa: BLE001 告警链路吞掉一切，任务语义优先
                last_error = e
                if attempt < self.max_attempts:
                    time.sleep(0.5 * attempt)
        logger.warning("webhook 告警发送失败（已尝试 %d 次，事件=%s）：%s", self.max_attempts, event, last_error)
        return False


def alerter_from_env(env=None) -> WebhookAlerter | None:
    """从环境构造告警器；未配置返回 None（runner 拿到 None 即完全走现状路径）。"""
    env = os.environ if env is None else env
    url = env.get(ALERT_WEBHOOK_ENV, "").strip()
    return WebhookAlerter(url) if url else None
