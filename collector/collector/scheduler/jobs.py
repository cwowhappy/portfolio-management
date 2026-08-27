import logging
import time

import psycopg
from apscheduler.schedulers.blocking import BlockingScheduler
from apscheduler.triggers.cron import CronTrigger

from collector.config import load
from collector.run_once import collect_once

logger = logging.getLogger(__name__)

MAX_ATTEMPTS = 3
RETRY_DELAY_SECONDS = 30


def _run(config) -> None:
    """有界重试执行采集：最多 3 次，每次失败记录日志，30s 后重试。"""
    for attempt in range(1, MAX_ATTEMPTS + 1):
        try:
            with psycopg.connect(config.database_url) as conn:
                collect_once(conn, config)
            return
        except Exception:
            logger.exception("采集失败（第 %d/%d 次）", attempt, MAX_ATTEMPTS)
            if attempt < MAX_ATTEMPTS:
                time.sleep(RETRY_DELAY_SECONDS)
            else:
                raise


def main() -> None:
    logging.basicConfig(level=logging.INFO)
    config = load()
    scheduler = BlockingScheduler()
    scheduler.add_job(
        lambda: _run(config),
        CronTrigger(day_of_week="mon-fri", hour=15, minute=30),
        id="valuation_daily_snapshot",
        coalesce=True,
    )
    scheduler.start()


if __name__ == "__main__":
    main()
