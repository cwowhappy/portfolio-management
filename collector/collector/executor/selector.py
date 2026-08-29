from datetime import datetime, timezone

from collector.model.health import SourceHealth

CIRCUIT_BREAK_THRESHOLD = 3
COOLDOWN_SECONDS = 600


def _state(h: SourceHealth, now) -> str:
    if h.consecutive_failures >= CIRCUIT_BREAK_THRESHOLD:
        if h.last_failure_at and (now - h.last_failure_at).total_seconds() < COOLDOWN_SECONDS:
            return "open"
        return "half_open"
    return "closed"


def _score(h: SourceHealth) -> float:
    if h.total_runs == 0:
        return 50.0
    success_rate = h.success_runs / h.total_runs
    latency_penalty = min(30.0, (h.last_latency_ms or 0) / 1000 * 5)
    failure_penalty = min(40.0, h.consecutive_failures * 20)
    return 100 * success_rate - latency_penalty - failure_penalty


class SourceSelector:
    def select(self, sources, health):
        now = datetime.now(timezone.utc)
        candidates = []
        probes = []
        for priority, src in enumerate(sources):
            h = health.get(src.source_id, SourceHealth(src.source_id))
            state = _state(h, now)
            if state == "open":
                continue
            item = (_score(h), priority, src)
            if state == "half_open":
                probes.append(item)
            else:
                candidates.append(item)
        candidates.sort(key=lambda x: (-x[0], x[1]))
        probes.sort(key=lambda x: x[1])
        # 健康候选优先：半开状态的探针只在无健康候选时兜底。
        return [s for _, _, s in candidates + probes]

    def record_success(self, h: SourceHealth, latency_ms: int) -> SourceHealth:
        h.total_runs += 1
        h.success_runs += 1
        h.consecutive_failures = 0
        h.last_latency_ms = latency_ms
        h.last_success_at = datetime.now(timezone.utc)
        h.score = _score(h)
        return h

    def record_failure(self, h: SourceHealth, error: str) -> SourceHealth:
        h.total_runs += 1
        h.consecutive_failures += 1
        h.last_failure_at = datetime.now(timezone.utc)
        h.last_error = error
        h.score = _score(h)
        return h
