import datetime as dt
import glob
import logging
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
from collector.repositories.runs import RunRepository
from collector.repositories.tasks import TASK_COLS, TaskRepository
from collector.scheduler.calendar import TradingCalendar
from collector.scheduler.runner import TaskRunner
from collector.sources.plugins import (
    AllASpotBackupSource,
    IndexConstituentSource,
    IndexValuationSource,
    IndustryUniverseSource,
    ShenwanMappingSource,
    StockFinancialSource,
    StockValuationDailySource,
    TreasuryCurveSource,
    make_index_dividend_fetch,
)
from collector.sources.registry import SourceRegistry
from collector.store.writer import Store
from collector.validators.registry import ValidatorRegistry

logger = logging.getLogger(__name__)

# 交易日历每月刷新一次（每月 1 日 03:10），upsert 幂等。
CALENDAR_REFRESH_CRON = "10 3 1 * *"
# 日历最新日期落后当天超过该天数时打 warning 留痕。
CALENDAR_STALE_DAYS = 15


def load_task_defs(dir_path: str) -> list[dict]:
    out = []
    for path in sorted(glob.glob(os.path.join(dir_path, "*.yaml"))):
        with open(path, encoding="utf-8") as f:
            out.append(yaml.safe_load(f))
    return out


# L5：YAML 任务字段集是封闭契约，键集合与 TaskRepository 消费列一一对应。
TASK_DEF_KEYS = frozenset(TASK_COLS)


def _validate_task_keys(t):
    """缺必填键或含未知键都 fail-fast：缺键会静默写入 NULL，未知键是死配置。"""
    code = t.get("task_code", "<未知>")
    missing = TASK_DEF_KEYS - t.keys()
    if missing:
        raise ValueError(f"任务 {code} 缺必填键: {sorted(missing)}")
    unknown = t.keys() - TASK_DEF_KEYS
    if unknown:
        raise ValueError(f"任务 {code} 含未知键: {sorted(unknown)}")


def seed_tasks(conn, task_defs):
    repo = TaskRepository(conn)
    for t in task_defs:
        _validate_task_keys(t)
        repo.upsert(t)
    # reconcile：YAML 删除的任务在库中残留且仍 enabled，会被调度——停用（保留历史与回溯）。
    codes = {t["task_code"] for t in task_defs}
    for stale in sorted(repo.all_codes() - codes):
        logger.warning("任务 %s 不在当前 YAML 定义中，seed reconcile 停用", stale)
        repo.disable(stale)


def assemble_collector(row, registries):
    sources = [registries["source"].get(spec) for spec in row["source_ids"]]
    converter = registries["converter"].get(row["converter"])
    calc = registries["calc"].get(row["calc"]) if row.get("calc") else None
    validator = registries["validator"].get(row["validator"]) if row.get("validator") else None
    return Collector(
        task_code=row["task_code"],
        task_name=row["task_name"],
        sources=sources,
        converter=converter,
        calc=calc,
        validator=validator,
        target_table=row["target_table"],
        schedule=row["schedule"],
        enabled=row["enabled"],
        trading_day_gated=row["trading_day_gated"],
        retry_max=row["retry_max"],
        retry_backoff=row["retry_backoff"],
    )


def make_trigger(schedule):
    if schedule["type"] == "cron":
        return CronTrigger.from_crontab(schedule["cron"])
    if schedule["type"] == "interval":
        return IntervalTrigger(**{k: v for k, v in schedule.items() if k != "type"})
    raise ValueError(f"未知调度类型: {schedule['type']}")


def _run_task_job(runner, task):
    """S3：job 自己兜底所有异常——异常逃出 job 会被 APScheduler 静默丢弃该任务的后续调度。"""
    try:
        runner.run(task)
    except Exception:
        logger.exception("任务 %s 调度运行失败", task.task_code)


def build_scheduler(tasks, runner, never_succeeded=None, calendar_refresher=None):
    scheduler = BlockingScheduler()
    now = dt.datetime.now()
    for task in tasks:
        kwargs = {}
        if never_succeeded and task.task_code in never_succeeded:
            # 冷启动补跑：从未成功运行过的任务（如周频 shenwan_mapping）立即执行一次，
            # 否则要等首个调度周期，期间依赖它的任务必失败。
            kwargs["next_run_time"] = now
        scheduler.add_job(
            lambda t=task: _run_task_job(runner, t),
            trigger=make_trigger(task.schedule),
            id=task.task_code,
            coalesce=True,
            max_instances=1,
            misfire_grace_time=3600,
            **kwargs,
        )
    if calendar_refresher is not None:
        scheduler.add_job(
            calendar_refresher,
            trigger=CronTrigger.from_crontab(CALENDAR_REFRESH_CRON),
            id="refresh_trading_calendar",
            coalesce=True,
            max_instances=1,
            misfire_grace_time=86400,
        )
    return scheduler


def _field_columns():
    return {
        "field_mapping_all_a": FieldMappingConverter(
            {
                "code": {"from": "代码", "type": "str"},
                "name": {"from": "名称", "type": "str"},
                "pe": {"from": "市盈率-动态", "type": "numeric"},
                "pb": {"from": "市净率", "type": "numeric"},
                "market_cap": {"from": "总市值", "type": "numeric"},
            }
        ),
        "field_mapping_index": FieldMappingConverter(
            {
                "trading_day": {"from": "trading_day", "type": "str"},
                "index_code": {"from": "index_code", "type": "str"},
                "index_name": {"from": "index_name", "type": "str"},
                "pe": {"from": "pe", "type": "numeric"},
                "pb": {"from": "pb", "type": "numeric"},
                "dividend_yield": {"from": "dividend_yield", "type": "numeric"},
            }
        ),
        "field_mapping_sw": FieldMappingConverter(
            {
                "stock_code": {"from": "code", "type": "str"},
                "stock_name": {"from": "stock_name", "type": "str"},
                "industry_code": {"from": "industry_code", "type": "str"},
                "industry_name": {"from": "industry_name", "type": "str"},
            }
        ),
        "field_mapping_curve": FieldMappingConverter(
            {
                "trading_day": {"from": "trading_day", "type": "str"},
                "term": {"from": "term", "type": "str"},
                "yield": {"from": "yield", "type": "numeric"},
            }
        ),
        "field_mapping_constituent": FieldMappingConverter(
            {
                "index_code": {"from": "index_code", "type": "str"},
                "stock_code": {"from": "stock_code", "type": "str"},
                "stock_name": {"from": "stock_name", "type": "str", "default": None},
                "weight": {"from": "weight", "type": "numeric"},
            }
        ),
        "field_mapping_industry": FieldMappingConverter(
            {
                "code": {"from": "代码", "type": "str"},
                "name": {"from": "名称", "type": "str"},
                "pe": {"from": "市盈率-动态", "type": "numeric"},
                "pb": {"from": "市净率", "type": "numeric"},
                "market_cap": {"from": "总市值", "type": "numeric"},
                "industry_code": {"from": "industry_code", "type": "str"},
                "industry_name": {"from": "industry_name", "type": "str"},
                "roe": {"from": "roe", "type": "numeric"},
                "dividend_yield": {"from": "dividend_yield", "type": "numeric"},
            }
        ),
        "field_mapping_stock_valuation": FieldMappingConverter(
            {
                "stock_code": {"from": "stock_code", "type": "str"},
                "stock_name": {"from": "stock_name", "type": "str"},
                "pe_ttm": {"from": "pe_ttm", "type": "numeric"},
                "pb": {"from": "pb", "type": "numeric"},
                "dividend_yield": {"from": "dividend_yield", "type": "numeric"},
                "total_mv": {"from": "total_mv", "type": "numeric"},
                "circ_mv": {"from": "circ_mv", "type": "numeric"},
                "turnover_rate": {"from": "turnover_rate", "type": "numeric"},
            }
        ),
        "field_mapping_stock_financial": FieldMappingConverter(
            {
                "report_date": {"from": "report_date", "type": "str"},
                "stock_code": {"from": "stock_code", "type": "str"},
                "roe": {"from": "roe", "type": "numeric"},
                "roa": {"from": "roa", "type": "numeric"},
                "gross_margin": {"from": "gross_margin", "type": "numeric"},
                "debt_to_assets": {"from": "debt_to_assets", "type": "numeric"},
                "current_ratio": {"from": "current_ratio", "type": "numeric"},
                "revenue_yoy": {"from": "revenue_yoy", "type": "numeric"},
                "netprofit_yoy": {"from": "netprofit_yoy", "type": "numeric"},
            }
        ),
    }


def build_registries(config):
    def pro():
        return ts.pro_api(config.tushare_token)

    def conn_factory():
        return psycopg.connect(config.database_url)

    source_reg = SourceRegistry(
        tushare_token=config.tushare_token,
        plugins={
            "shenwan_mapping": ShenwanMappingSource("shenwan_mapping", pro_factory=pro),
            "index_valuation": IndexValuationSource(
                "index_valuation",
                pro_factory=pro,
                dividend_fetch=make_index_dividend_fetch(pro),
            ),
            "industry_universe": IndustryUniverseSource(
                "industry_universe", conn_factory=conn_factory, pro_factory=pro
            ),
            "treasury_curve": TreasuryCurveSource("treasury_curve", conn_factory=conn_factory),
            "index_constituent": IndexConstituentSource("index_constituent", pro_factory=pro),
            "all_a_spot_backup": AllASpotBackupSource("all_a_spot_backup", pro_factory=pro),
            "stock_valuation_daily": StockValuationDailySource("stock_valuation_daily", pro_factory=pro),
            "stock_financial": StockFinancialSource("stock_financial", pro_factory=pro),
        },
    )
    converter_reg = ConverterRegistry(plugins=_field_columns())
    calc_reg = CalcRegistry(plugins={"snapshot": SnapshotCalc(), "industry_weighted": IndustryValuationCalc()})
    validator_reg = ValidatorRegistry()
    return {"source": source_reg, "converter": converter_reg, "calc": calc_reg, "validator": validator_reg}


def refresh_calendar(conn):
    """从数据源拉取最新交易日历并 upsert（ON CONFLICT DO NOTHING，幂等）。"""
    df = ak.tool_trade_date_hist_sina()
    dates = [dt.date.fromisoformat(str(d)) for d in df["trade_date"]]
    with conn.cursor() as cur:
        cur.executemany(
            "INSERT INTO trading_calendar (trade_date) VALUES (%s) ON CONFLICT DO NOTHING", [(d,) for d in dates]
        )
    conn.commit()
    logger.info("交易日历已刷新：%d 个交易日", len(dates))


def check_calendar_staleness(conn, today=None):
    """日历最新日期落后当天超过 CALENDAR_STALE_DAYS 天时打 warning 留痕。"""
    today = today or dt.date.today()
    with conn.cursor() as cur:
        cur.execute("SELECT max(trade_date) FROM trading_calendar")
        latest = cur.fetchone()[0]
    if latest is None or (today - latest).days > CALENDAR_STALE_DAYS:
        logger.warning("交易日历最新日期 %s 落后当天超过 %d 天，交易日判断可能失真", latest, CALENDAR_STALE_DAYS)


def refresh_calendar_job(database_url):
    """APScheduler 定期刷新入口：自建连接，异常只记日志不拖垮调度器。"""
    try:
        with psycopg.connect(database_url) as conn:
            refresh_calendar(conn)
            check_calendar_staleness(conn)
    except Exception:
        logger.exception("交易日历定期刷新失败")


def load_calendar(conn):
    with conn.cursor() as cur:
        cur.execute("SELECT trade_date FROM trading_calendar")
        dates = {r[0] for r in cur.fetchall()}
    return TradingCalendar(dates)


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    config = load()
    alembic_cfg = AlembicConfig("migrations/alembic.ini")
    alembic_cfg.set_main_option("sqlalchemy.url", config.database_url)
    command.upgrade(alembic_cfg, "head")

    registries = build_registries(config)
    with psycopg.connect(config.database_url) as conn:
        refresh_calendar(conn)
        check_calendar_staleness(conn)
        seed_tasks(conn, load_task_defs("tasks"))
        rows = TaskRepository(conn).list_enabled()
        tasks = [assemble_collector(r, registries) for r in rows]
        never_succeeded = RunRepository(conn).never_succeeded([t.task_code for t in tasks])

    # 按需查库的日历：长期运行不依赖启动时的内存快照。
    calendar = TradingCalendar(conn_factory=lambda: psycopg.connect(config.database_url))
    selector = SourceSelector()
    store = Store()
    executor = Executor(selector, store)
    runner = TaskRunner(config.database_url, calendar, executor)
    scheduler = build_scheduler(
        tasks,
        runner,
        never_succeeded=never_succeeded,
        calendar_refresher=lambda: refresh_calendar_job(config.database_url),
    )
    logger.info("调度器启动：%d 个任务，冷启动补跑 %d 个", len(tasks), len(never_succeeded))
    scheduler.start()


if __name__ == "__main__":
    main()
