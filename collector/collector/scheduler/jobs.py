import json
import glob
import os

import yaml

from apscheduler.schedulers.blocking import BlockingScheduler
from apscheduler.triggers.cron import CronTrigger
from apscheduler.triggers.interval import IntervalTrigger

from collector.model.task import Collector
from collector.repositories.tasks import TaskRepository


def load_task_defs(dir_path: str) -> list[dict]:
    out = []
    for path in sorted(glob.glob(os.path.join(dir_path, "*.yaml"))):
        with open(path, encoding="utf-8") as f:
            out.append(yaml.safe_load(f))
    return out


def seed_tasks(conn, task_defs):
    repo = TaskRepository(conn)
    for t in task_defs:
        repo.upsert(t)


def assemble_collector(row, registries):
    sources = [registries["source"].get(spec) for spec in row["source_ids"]]
    converter = registries["converter"].get(row["converter"])
    calc = registries["calc"].get(row["calc"]) if row.get("calc") else None
    validator = registries["validator"].get(row["validator"])
    return Collector(
        task_code=row["task_code"], task_name=row["task_name"], sources=sources,
        converter=converter, calc=calc, validator=validator, target_table=row["target_table"],
        schedule=row["schedule"], enabled=row["enabled"],
        trading_day_gated=row["trading_day_gated"], retry_max=row["retry_max"],
        retry_backoff=row["retry_backoff"],
    )


def make_trigger(schedule):
    if schedule["type"] == "cron":
        return CronTrigger.from_crontab(schedule["cron"])
    if schedule["type"] == "interval":
        return IntervalTrigger(**{k: v for k, v in schedule.items() if k != "type"})
    raise ValueError(f"未知调度类型: {schedule['type']}")


def build_scheduler(tasks, runner):
    scheduler = BlockingScheduler()
    for task in tasks:
        scheduler.add_job(
            lambda t=task: runner.run(t),
            trigger=make_trigger(task.schedule),
            id=task.task_code, coalesce=True, max_instances=1,
        )
    return scheduler
