from unittest.mock import MagicMock

from collector.backfill import run_backfill
from collector.model.task import Collector
from collector.model.run import RunResult, STATUS_SUCCESS, MODE_BACKFILL


def test_backfill_calls_runner_with_range():
    runner = MagicMock()
    runner.run.return_value = RunResult("t", MODE_BACKFILL, STATUS_SUCCESS)
    task = Collector("t", "t", [], MagicMock(), None, MagicMock(), "x", {})
    run_backfill(runner, task, "2020-01-01", "2026-08-28")
    runner.run.assert_called_once_with(task, mode="backfill",
                                       params={"start": "2020-01-01", "end": "2026-08-28"}, force=True)
