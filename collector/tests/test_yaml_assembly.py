"""全量任务 YAML 装配冒烟（L5/L2）与 registry / make_trigger 行为。

用真实 tasks/ 目录 + 真实 build_registries 走完整装配链路：
load_task_defs → build_registries → assemble_collector，全量任务全部装配成功即通过。
Config 用占位值：插件源与 ConfigurableSource 均为惰性构造，装配不触外部 API。
"""

import datetime as dt
from pathlib import Path

import pandas as pd
import pytest
from apscheduler.triggers.cron import CronTrigger
from apscheduler.triggers.interval import IntervalTrigger

from collector.config import Config
from collector.scheduler.jobs import assemble_collector, build_registries, load_task_defs, make_trigger
from collector.sources.announcements import ANN_COLUMNS
from collector.sources.base import SourceError
from collector.sources.news import NEWS_COLUMNS

TASKS_DIR = Path(__file__).resolve().parent.parent / "tasks"

EXPECTED_TASKS = {
    "all_a_valuation",
    "announcement_morning",
    "announcement_night",
    "bond_index_close",
    "etf_basic",
    "etf_close",
    "etf_tracking_error",
    "gold_etf_close",
    "index_close",
    "index_constituent",
    "index_valuation",
    "industry_index_close",
    "industry_valuation",
    "industry_valuation_backfill",
    "news_fast",
    "news_night",
    "shenwan_mapping",
    "stock_financial",
    "stock_valuation_daily",
    "tracking_index_close",
    "treasury_yield_curve",
}


def _registries():
    return build_registries(Config(database_url="postgresql://placeholder", tushare_token="placeholder"))


def test_load_task_defs_reads_all_yaml():
    defs = load_task_defs(str(TASKS_DIR))
    assert {d["task_code"] for d in defs} == EXPECTED_TASKS


def test_all_yaml_tasks_assemble():
    defs = load_task_defs(str(TASKS_DIR))
    regs = _registries()
    collectors = [assemble_collector(d, regs) for d in defs]
    assert {c.task_code for c in collectors} == EXPECTED_TASKS
    for c in collectors:
        assert c.sources, c.task_code
        assert c.converter is not None
        # 每个任务的调度定义都能构建出触发器
        assert make_trigger(c.schedule) is not None


def test_all_a_valuation_yaml_has_tushare_backup_source():
    """C-3.1：all_a_valuation 主源 akshare_spot_em + 备源 all_a_spot_backup（降级）。"""
    defs = {d["task_code"]: d for d in load_task_defs(str(TASKS_DIR))}
    sources = [s["source_id"] for s in defs["all_a_valuation"]["source_ids"]]
    assert sources == ["akshare_spot_em", "all_a_spot_backup"]


@pytest.mark.parametrize(
    ("code", "cron"),
    [("news_fast", "*/10 7-23 * * *"), ("news_night", "0 0-6/2 * * *")],
)
def test_news_tasks_yaml(code, cron):
    """MS-20 设计 §6：双任务同表（intelligence_news_raw）、同源序（东财主→新浪降级）、
    双档变频只差 task_code/cron；新闻跨非交易日，trading_day_gated 必须 false。
    night 的 5 段式等价改写（设计原文 6 段式 from_crontab 不识别）见 YAML 注释。"""
    defs = {d["task_code"]: d for d in load_task_defs(str(TASKS_DIR))}
    d = defs[code]
    assert d["target_table"] == "intelligence_news_raw"
    assert [s["source_id"] for s in d["source_ids"]] == ["eastmoney_fast_news", "sina_zhibo_news"]
    assert all(s["type"] == "plugin" for s in d["source_ids"])
    assert d["converter"] == "field_mapping_news"
    assert d["schedule"] == {"type": "cron", "cron": cron}
    assert d["trading_day_gated"] is False
    assert d["retry_max"] == 2
    assert d["retry_backoff"] == "fixed"
    assert d["calc"] is None
    # 7 列输出契约的业务键与非空列 hard 校验（brief Step 1）
    assert d["validator"] == [
        {"field": "external_id", "check": "required", "level": "hard"},
        {"field": "title", "check": "required", "level": "hard"},
        {"field": "published_at", "check": "not_null", "level": "hard"},
    ]


@pytest.mark.parametrize(
    ("code", "cron"),
    [("announcement_night", "20 22 * * MON-FRI"), ("announcement_morning", "20 6 * * MON-FRI")],
)
def test_announcement_tasks_yaml(code, cron):
    """MS-21 设计 §6：公告双任务同表（intelligence_announcement）、同源序（巨潮主→东财
    降级），双档变频只差 task_code/cron；晚间 22:20 采披露高峰、早间 06:20 补采（回看
    窗口覆盖周末缺口）。公告披露跨非交易日，trading_day_gated 必须 false。validator 四
    必填 hard——stock_code 刻意不限 A 股码格式（东财流混可转债代码，按 codes[0] 契约
    原样落库，限格式会剔行）。"""
    defs = {d["task_code"]: d for d in load_task_defs(str(TASKS_DIR))}
    d = defs[code]
    assert d["target_table"] == "intelligence_announcement"
    assert [s["source_id"] for s in d["source_ids"]] == ["cninfo_ann", "eastmoney_ann"]
    assert all(s["type"] == "plugin" for s in d["source_ids"])
    assert d["converter"] == "field_mapping_announcement"
    assert d["schedule"] == {"type": "cron", "cron": cron}
    assert d["trading_day_gated"] is False
    assert d["retry_max"] == 2
    assert d["retry_backoff"] == "fixed"
    assert d["calc"] is None
    # 9 列输出契约的业务键与 NOT NULL 列 hard 校验（brief Step 1）
    assert d["validator"] == [
        {"field": "external_id", "check": "required", "level": "hard"},
        {"field": "stock_code", "check": "required", "level": "hard"},
        {"field": "title", "check": "required", "level": "hard"},
        {"field": "published_at", "check": "not_null", "level": "hard"},
    ]


def test_field_mapping_announcement_converter_passthrough():
    """field_mapping_announcement 9 列同名映射：published_at 保持 datetime 对象
    （psycopg 原生适配 TIMESTAMPTZ，勿 str 强转）、major 保持 bool（type:None 跳过
    _coerce 强转——str() 化会把 True 变 "True" 字符串）、可空列 None 透传不炸。"""
    regs = _registries()
    converter = regs["converter"].get("field_mapping_announcement")
    published = dt.datetime(2026, 9, 28, 0, 0, 0, tzinfo=dt.timezone(dt.timedelta(hours=8)))
    raw = pd.DataFrame(
        [
            {
                "source": "cninfo",
                "external_id": "122236472",
                "stock_code": "600519",
                "stock_name": "贵州茅台",
                "title": "贵州茅台2025年半年度报告",
                "ann_type_source": "category_bndbg_szsh",
                "major": True,
                "published_at": published,
                "pdf_url": "https://static.cninfo.com.cn/finalpage/2026-09-28/122236472.PDF",
            },
            {
                # 东财流混可转债代码条目（探测报告 §4）：codes[0] 契约原样透传，不限格式
                "source": "eastmoney_ann",
                "external_id": "AP202609281234567890",
                "stock_code": "113583",
                "stock_name": None,
                "title": "某某转债2026年跟踪评级报告",
                "ann_type_source": None,
                "major": False,
                "published_at": dt.datetime(2026, 9, 29, 20, 41, 29, tzinfo=dt.timezone(dt.timedelta(hours=8))),
                "pdf_url": None,
            },
        ],
        columns=ANN_COLUMNS,
    )
    records = converter.convert(raw)
    assert len(records) == 2
    assert records[0]["source"] == "cninfo"
    assert records[0]["published_at"] == published  # datetime 对象未被 str() 化
    assert records[0]["major"] is True  # bool 未被 str() 化
    assert records[1]["stock_name"] is None  # 可空列 None 透传
    assert records[1]["ann_type_source"] is None
    assert records[1]["pdf_url"] is None
    assert records[1]["stock_code"] == "113583"  # 可转债代码原样
    # 空帧（增量窗口无新披露）→ 空记录，不抛错
    assert converter.convert(pd.DataFrame(columns=ANN_COLUMNS)) == []


def test_field_mapping_news_converter_passthrough():
    """field_mapping_news 7 列同名映射：published_at 保持 datetime 对象（psycopg 原生
    适配 TIMESTAMPTZ，勿 str 强转）、stock_tags 保持 JSON 字符串原样透传。"""
    regs = _registries()
    converter = regs["converter"].get("field_mapping_news")
    published = dt.datetime(2026, 9, 29, 10, 29, 16, tzinfo=dt.timezone(dt.timedelta(hours=8)))
    raw = pd.DataFrame(
        [
            {
                "source": "eastmoney_724",
                "external_id": "202609291029161",
                "title": "央行开展1000亿元逆回购",
                "summary": "央行公告内容",
                "published_at": published,
                "url": "https://finance.eastmoney.com/a/202609291029161.html",
                "stock_tags": '["1.600825", "90.BK1365"]',
            },
            {
                "source": "sina_zhibo",
                "external_id": "152:10086",
                "title": "快讯标题",
                "summary": "【快讯标题】正文",
                "published_at": published,
                "url": None,
                "stock_tags": '[{"code": "300226", "name": "上海钢联"}]',
            },
        ],
        columns=NEWS_COLUMNS,
    )
    records = converter.convert(raw)
    assert len(records) == 2
    assert records[0]["source"] == "eastmoney_724"
    assert records[0]["published_at"] == published  # datetime 对象未被 str() 化
    assert records[0]["stock_tags"] == '["1.600825", "90.BK1365"]'
    assert records[1]["url"] is None
    # 空帧（*/10 增量无新条目）→ 空记录，不抛错
    assert converter.convert(pd.DataFrame(columns=NEWS_COLUMNS)) == []


def test_make_trigger_cron():
    trigger = make_trigger({"type": "cron", "cron": "30 15 * * 1-5"})
    assert isinstance(trigger, CronTrigger)


def test_make_trigger_interval():
    trigger = make_trigger({"type": "interval", "days": 7})
    assert isinstance(trigger, IntervalTrigger)


def test_make_trigger_unknown_type_raises():
    with pytest.raises(ValueError, match="未知调度类型"):
        make_trigger({"type": "hourly"})


def test_registries_get_registered():
    regs = _registries()
    source = regs["source"].get({"source_id": "ak_x", "type": "akshare", "call": "stock_zh_a_spot_em"})
    assert source.source_id == "ak_x"
    plugin = regs["source"].get({"source_id": "p", "type": "plugin", "class": "shenwan_mapping"})
    assert plugin is regs["source"].plugins["shenwan_mapping"]
    assert regs["converter"].get("field_mapping_all_a") is not None
    assert regs["calc"].get("snapshot") is not None
    validator = regs["validator"].get([{"check": "min_rows", "value": 1, "level": "hard"}])
    assert validator is not None


@pytest.mark.parametrize("kind", ["source", "converter", "calc", "validator"])
def test_registries_get_unregistered_raises(kind):
    regs = _registries()
    with pytest.raises(SourceError, match="未注册"):
        if kind == "source":
            regs[kind].get({"source_id": "x", "type": "plugin", "class": "nope"})
        else:
            regs[kind].get("nope")


def test_source_registry_unknown_type_raises():
    regs = _registries()
    with pytest.raises(SourceError, match="未知源类型"):
        regs["source"].get({"source_id": "x", "type": "ftp", "call": "f"})
