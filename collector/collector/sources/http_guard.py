"""requests 缺省超时注入（2026-10-15 审查 H1/L3 收口）。

akshare 内部 4 处 requests.get/post 不传 timeout（.venv 实证：requests 缺省时显式传
None 至 urllib3 → sock.settimeout(None) 覆盖全局默认，socket.setdefaulttimeout 对本
问题无效），静默丢包场景下被超时放弃的 fetch 线程永久存活累积。此处包装
requests.get/post：缺省（未传或 None）注入默认秒数，显式传参不覆盖（tushare
timeout=30 不受影响）。进程级幂等：cli/jobs 两入口各调一次，重复安装不叠加包装。
"""

import requests


def _wrap(method, seconds: int):
    def guarded(url, *args, **kwargs):
        if kwargs.get("timeout") is None:
            kwargs["timeout"] = seconds
        return method(url, *args, **kwargs)

    return guarded


_installed = False


def install_default_http_timeout(seconds: int = 90) -> None:
    """包装 requests.get/post：缺省调用注入 seconds 超时；进程级幂等，重复安装直接返回。"""
    global _installed
    if _installed:
        return
    requests.get = _wrap(requests.get, seconds)
    requests.post = _wrap(requests.post, seconds)
    _installed = True
