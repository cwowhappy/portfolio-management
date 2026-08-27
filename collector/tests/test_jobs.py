"""scheduler/jobs.py `_run` 的有界重试逻辑单测（Fix 2）。"""
from types import SimpleNamespace
from unittest import mock

import collector.scheduler.jobs as jobs


def _config():
    return SimpleNamespace(database_url="postgresql://x")


def test_run_retries_then_succeeds():
    conn = mock.MagicMock()
    failures = {"count": 0}

    def flaky_collect(_conn, _config):
        failures["count"] += 1
        if failures["count"] < jobs.MAX_ATTEMPTS:
            raise RuntimeError("transient failure")

    with mock.patch.object(jobs.psycopg, "connect", return_value=conn), mock.patch.object(
        jobs, "collect_once", side_effect=flaky_collect
    ), mock.patch.object(jobs.time, "sleep") as sleep:
        jobs._run(_config())

    assert failures["count"] == jobs.MAX_ATTEMPTS
    assert sleep.call_count == jobs.MAX_ATTEMPTS - 1


def test_run_raises_after_max_attempts():
    conn = mock.MagicMock()

    with mock.patch.object(jobs.psycopg, "connect", return_value=conn), mock.patch.object(
        jobs, "collect_once", side_effect=RuntimeError("boom")
    ), mock.patch.object(jobs.time, "sleep") as sleep:
        try:
            jobs._run(_config())
            raise AssertionError("_run 应在达到最大重试次数后抛出")
        except RuntimeError:
            pass

    assert sleep.call_count == jobs.MAX_ATTEMPTS - 1
