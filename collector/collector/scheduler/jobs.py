from apscheduler.schedulers.blocking import BlockingScheduler
from apscheduler.triggers.cron import CronTrigger

import psycopg

from collector.config import load
from collector.run_once import collect_once


def _run(config) -> None:
    with psycopg.connect(config.database_url) as conn:
        collect_once(conn, config)


def main() -> None:
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
