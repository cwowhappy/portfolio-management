import datetime as dt
import glob
import json
import os

import akshare as ak
import psycopg
import tushare as ts
import yaml
from alembic import command
from alembic.config import Config as AlembicConfig
from apscheduler.schedulers.blocking import BlockingScheduler
from apscheduler.triggers.cron import CronTrigger
from apscheduler.triggers.interval import IntervalTrigger

from collector.calc.registry import CalcRegistry
from collector.calc.snapshot import IndustryValuationCalc, SnapshotCalc
from collector.config import load
from collector.converters.field_mapping import FieldMappingConverter
from collector.converters.registry import ConverterRegistry
from collector.executor.executor import Executor
from collector.executor.selector import SourceSelector
from collector.model.task import Collector
from collector.repositories.health import HealthRepository
from collector.repositories.runs import RunRepository
from collector.repositories.tasks import TaskRepository
from collector.scheduler.calendar import TradingCalendar
from collector.scheduler.runner import TaskRunner
from collector.sources.plugins import (
    IndexConstituentSource,
    IndexValuationSource,
    IndustryUniverseSource,
    ShenwanMappingSource,
    TreasuryCurveSource,
)
from collector.sources.registry import SourceRegistry
from collector.store.writer import Store
from collector.validators.registry import ValidatorRegistry


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
    validator = registries["validator"].get(row["validator"]) if row.get("validator") else None
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


def _field_columns():
    return {
        "field_mapping_all_a": FieldMappingConverter({
            "code": {"from": "代码", "type": "str"},
            "name": {"from": "名称", "type": "str"},
            "pe": {"from": "市盈率-动态", "type": "numeric"},
            "pb": {"from": "市净率", "type": "numeric"},
            "market_cap": {"from": "总市值", "type": "numeric"},
        }),
        "field_mapping_index": FieldMappingConverter({
            "trading_day": {"from": "trading_day", "type": "str"},
            "index_code": {"from": "index_code", "type": "str"},
            "index_name": {"from": "index_name", "type": "str"},
            "pe": {"from": "pe", "type": "numeric"},
            "pb": {"from": "pb", "type": "numeric"},
            "dividend_yield": {"from": "dividend_yield", "type": "numeric"},
        }),
        "field_mapping_sw": FieldMappingConverter({
            "stock_code": {"from": "code", "type": "str"},
            "stock_name": {"from": "code", "type": "str"},
            "industry_code": {"from": "industry_code", "type": "str"},
            "industry_name": {"from": "industry_name", "type": "str"},
        }),
        "field_mapping_curve": FieldMappingConverter({
            "trading_day": {"from": "trading_day", "type": "str"},
            "term": {"from": "term", "type": "str"},
            "yield": {"from": "yield", "type": "numeric"},
        }),
        "field_mapping_constituent": FieldMappingConverter({
            "index_code": {"from": "index_code", "type": "str"},
            "stock_code": {"from": "stock_code", "type": "str"},
            "stock_name": {"from": "stock_name", "type": "str", "default": None},
            "weight": {"from": "weight", "type": "numeric"},
        }),
        "field_mapping_industry": FieldMappingConverter({
            "code": {"from": "代码", "type": "str"},
            "name": {"from": "名称", "type": "str"},
            "pe": {"from": "市盈率-动态", "type": "numeric"},
            "pb": {"from": "市净率", "type": "numeric"},
            "market_cap": {"from": "总市值", "type": "numeric"},
            "industry_code": {"from": "industry_code", "type": "str"},
            "industry_name": {"from": "industry_name", "type": "str"},
        }),
    }


def build_registries(config):
    pro = lambda: ts.pro_api(config.tushare_token)
    conn_factory = lambda: psycopg.connect(config.database_url)
    source_reg = SourceRegistry(tushare_token=config.tushare_token, plugins={
        "shenwan_mapping": ShenwanMappingSource("shenwan_mapping", pro_factory=pro),
        "index_valuation": IndexValuationSource("index_valuation", pro_factory=pro),
        "industry_universe": IndustryUniverseSource("industry_universe", conn_factory=conn_factory),
        "treasury_curve": TreasuryCurveSource("treasury_curve"),
        "index_constituent": IndexConstituentSource("index_constituent", pro_factory=pro),
    })
    converter_reg = ConverterRegistry(plugins=_field_columns())
    calc_reg = CalcRegistry(plugins={"snapshot": SnapshotCalc(), "industry_weighted": IndustryValuationCalc()})
    validator_reg = ValidatorRegistry()
    return {"source": source_reg, "converter": converter_reg, "calc": calc_reg, "validator": validator_reg}


def refresh_calendar(conn):
    with conn.cursor() as cur:
        cur.execute("SELECT count(*) FROM trading_calendar")
        if cur.fetchone()[0] > 0:
            return
    df = ak.tool_trade_date_hist_sina()
    dates = [dt.date.fromisoformat(str(d)) for d in df["trade_date"]]
    with conn.cursor() as cur:
        cur.executemany("INSERT INTO trading_calendar (trade_date) VALUES (%s) ON CONFLICT DO NOTHING",
                        [(d,) for d in dates])
    conn.commit()


def load_calendar(conn):
    with conn.cursor() as cur:
        cur.execute("SELECT trade_date FROM trading_calendar")
        dates = {r[0] for r in cur.fetchall()}
    return TradingCalendar(dates)


def main():
    config = load()
    alembic_cfg = AlembicConfig("migrations/alembic.ini")
    alembic_cfg.set_main_option("sqlalchemy.url", config.database_url)
    command.upgrade(alembic_cfg, "head")

    registries = build_registries(config)
    with psycopg.connect(config.database_url) as conn:
        refresh_calendar(conn)
        calendar = load_calendar(conn)
        seed_tasks(conn, load_task_defs("tasks"))
        rows = TaskRepository(conn).list_enabled()
        tasks = [assemble_collector(r, registries) for r in rows]

    selector = SourceSelector()
    store = Store()
    executor = Executor(selector, store)
    runner = TaskRunner(config.database_url, calendar, executor)
    scheduler = build_scheduler(tasks, runner)
    scheduler.start()


if __name__ == "__main__":
    main()
