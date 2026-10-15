"""C1（2026-10-15 审查 H1/L3）：requests 缺省超时注入。

akshare 内部 4 处 requests.get/post 不传 timeout，静默丢包下被放弃线程永久存活；
install_default_http_timeout 在缺省（未传或 None）时注入默认 90s，显式传参不覆盖
（tushare timeout=30 不受影响），进程级幂等（cli/jobs 两入口各调一次，重复安装不叠加）。
"""

import pytest
import requests

from collector.sources import http_guard
from collector.sources.http_guard import install_default_http_timeout


@pytest.fixture(autouse=True)
def restore_requests():
    """还原 requests.get/post 与安装标记：包装是进程级全局状态，用例间必须隔离。
    setup 即复位——cli.main 的入口测试会真实安装（幂等标记置位），不前置复位的话
    本文件内 install() 全部空转。"""
    orig_get, orig_post = requests.get, requests.post
    http_guard._installed = False
    yield
    requests.get, requests.post = orig_get, orig_post
    http_guard._installed = False


class _Recorder:
    """记录 kwargs 的假 requests 方法（替代真网络）。"""

    def __init__(self):
        self.kwargs = None

    def __call__(self, url, *args, **kwargs):
        self.kwargs = kwargs
        return "ok"


def test_injects_default_90_when_timeout_missing():
    rec = _Recorder()
    requests.get = rec
    install_default_http_timeout()
    requests.get("http://example.test/x")
    assert rec.kwargs["timeout"] == 90


def test_injects_default_90_when_timeout_none():
    rec = _Recorder()
    requests.get = rec
    install_default_http_timeout()
    requests.get("http://example.test/x", timeout=None)
    assert rec.kwargs["timeout"] == 90


def test_explicit_timeout_not_overridden():
    rec = _Recorder()
    requests.post = rec
    install_default_http_timeout()
    requests.post("http://example.test/x", timeout=5)  # tushare 显式 30s 同理不受影响
    assert rec.kwargs["timeout"] == 5


def test_install_is_idempotent():
    install_default_http_timeout()
    once = requests.get
    install_default_http_timeout()  # 二次安装不叠加包装
    assert requests.get is once
