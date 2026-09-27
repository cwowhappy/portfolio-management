"""数据新鲜度巡检（P0-3）：日终对账业务表 vs 交易日历。

设计（已与用户确认）：
- 交易日 17:30 独立 APScheduler job 调用（jobs.PATROL_CRON），非采集任务、不写业务表；
- 第一期 8 张规则表：6 张 trading_day 日频表对账「max(trading_day) < 最近已过交易日」，
  etf_basic / index_constituent 用 updated_at 阈值（周更 14 天 / 半年更 200 天）；
- shenwan_mapping（无时间列）与 stock_financial（季频 report_date 键）不进第一期；
- 交易日历为空时只报 calendar 一条（fail-safe：没有基准就不逐表判罚）。
"""

import datetime as dt
import logging

logger = logging.getLogger(__name__)

# trading_day 为键的日频表：期望 max(trading_day) == 最近已过交易日
DAILY_TABLES = (
    "valuation_snapshot",
    "industry_valuation",
    "index_valuation_history",
    "treasury_yield_curve",
    "stock_valuation_daily",
    "index_close_history",
)

# updated_at 时效表：table -> 允许的最大滞后天数（周更 14 天、半年更 200 天，含宽限）
UPDATED_AT_TABLES = {
    "etf_basic": 14,
    "index_constituent": 200,
}


def _iso(value):
    return value.isoformat() if value is not None else None


def run_freshness_patrol(conn, today=None) -> list[dict]:
    """对账 8 张规则表，返回滞留清单（空列表=全部新鲜）。只读不写。"""
    today = today or dt.date.today()
    expected = conn.execute("SELECT max(trade_date) FROM trading_calendar WHERE trade_date <= %s", (today,)).fetchone()[
        0
    ]
    if expected is None:
        return [{"kind": "calendar", "table": "trading_calendar", "latest": None, "expected": today.isoformat()}]

    findings = []
    for table in DAILY_TABLES:
        latest = conn.execute(f"SELECT max(trading_day) FROM {table}").fetchone()[0]  # noqa: S608 表名来自代码常量
        if latest is None or latest < expected:
            findings.append(
                {
                    "kind": "trading_day",
                    "table": table,
                    "latest": _iso(latest),
                    "expected": expected.isoformat(),
                }
            )

    now = dt.datetime.now(dt.UTC)
    for table, max_days in UPDATED_AT_TABLES.items():
        latest = conn.execute(f"SELECT max(updated_at) FROM {table}").fetchone()[0]  # noqa: S608 同上
        if latest is None or now - latest > dt.timedelta(days=max_days):
            findings.append(
                {
                    "kind": "updated_at",
                    "table": table,
                    "latest": _iso(latest),
                    "max_days": max_days,
                }
            )
    return findings
