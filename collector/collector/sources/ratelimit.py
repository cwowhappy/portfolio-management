"""FR-11 客户端限速：进程内线程安全的最小调用间隔限速器。

供并发拉取上游的源（如 StockFinancialSource 的 ThreadPoolExecutor）在每次
上游调用前调用 ``wait()``，使全局请求起始时刻至少间隔 ``min_interval`` 秒，
避免突发并发把上游限流打爆。等待在锁外进行，网络调用之间仍可重叠。
"""

import threading
import time


class RateLimiter:
    def __init__(self, min_interval: float = 0.0):
        self.min_interval = min_interval
        self._lock = threading.Lock()
        self._next_at = 0.0

    def wait(self) -> None:
        """阻塞至本调用被允许放行。min_interval<=0 时直接返回（不节流）。"""
        if self.min_interval <= 0:
            return
        with self._lock:
            now = time.monotonic()
            slot = max(now, self._next_at)
            self._next_at = slot + self.min_interval
        delay = slot - now
        if delay > 0:
            time.sleep(delay)
