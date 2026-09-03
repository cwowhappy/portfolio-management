"""C-5 / FR-11：客户端限速 RateLimiter 单测（进程内最小调用间隔，线程安全）。"""

import pytest

from collector.sources.ratelimit import RateLimiter


def _fake_time(monkeypatch):
    clock = {"now": 1000.0}
    sleeps = []

    def fake_monotonic():
        return clock["now"]

    def fake_sleep(seconds):
        sleeps.append(seconds)
        clock["now"] += seconds

    monkeypatch.setattr("collector.sources.ratelimit.time.monotonic", fake_monotonic)
    monkeypatch.setattr("collector.sources.ratelimit.time.sleep", fake_sleep)
    return clock, sleeps


def test_ratelimiter_disabled_when_min_interval_zero(monkeypatch):
    _, sleeps = _fake_time(monkeypatch)
    lim = RateLimiter(min_interval=0)
    lim.wait()
    lim.wait()
    assert sleeps == []


def test_ratelimiter_spaces_consecutive_calls(monkeypatch):
    clock, sleeps = _fake_time(monkeypatch)
    lim = RateLimiter(min_interval=0.2)
    lim.wait()  # 首次立即可放行
    lim.wait()  # 第二次需等 0.2s
    lim.wait()  # 第三次再等 0.2s
    assert clock["now"] == pytest.approx(1000.4)
    assert sleeps == pytest.approx([0.2, 0.2])


def test_ratelimiter_zero_interval_does_not_block(monkeypatch):
    clock, sleeps = _fake_time(monkeypatch)
    lim = RateLimiter(min_interval=0.0)
    lim.wait()
    assert clock["now"] == 1000.0
    assert sleeps == []
