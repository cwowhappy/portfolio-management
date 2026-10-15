"""跨域共享的源侧工具：日期归一化、日期参数解析、trade_cal 回溯、daily_basic 全市场估值。

daily_basic 相关助手被个股域（估值快照主/备源）与行业域（JOIN 源）复用，
属 tushare 口径公共件，故不归入单一域模块（2026-10-12 审查 C4 拆分）。
trade_cal 回溯（_open_days_desc）亦收敛于此：指数域（调仓日回溯）与 ETF 域
（_target_days 目标交易日序列，2026-10-15 审查 C6-P3-7）共用，口径单一。
"""

import datetime as dt
import re

from collector.sources.base import SourceError

_DATE_COMPACT = re.compile(r"\d{8}")


def normalize_date(value, name="date"):
    """日期统一归一化为 YYYYMMDD；兼容 date 对象、YYYY-MM-DD 与已规范的 YYYYMMDD 直通。"""
    if isinstance(value, dt.date):
        return value.strftime("%Y%m%d")
    s = str(value).strip()
    if _DATE_COMPACT.fullmatch(s):
        try:
            dt.date(int(s[:4]), int(s[4:6]), int(s[6:]))  # 校验是真实日期
        except ValueError as e:
            raise ValueError(f"非法日期参数 {name}={value!r}，期望 YYYY-MM-DD 或 YYYYMMDD") from e
        return s
    try:
        return dt.date.fromisoformat(s).strftime("%Y%m%d")
    except ValueError as e:
        raise ValueError(f"非法日期参数 {name}={value!r}，期望 YYYY-MM-DD 或 YYYYMMDD") from e


def _date_param(params, key):
    """取 date/start/end 日期参数；start/end 缺失时回退到 date 或今天，统一 YYYYMMDD。"""
    val = params.get(key) or params.get("date")
    if val:
        return normalize_date(val, key)
    return dt.date.today().strftime("%Y%m%d")


def _open_days_desc(pro, end_ymd, lookback=45):
    """end（含）往前 lookback 自然日内的开市日（trade_cal），按降序。

    lookback 取 45 自然日：index_weight 仅在调仓日发布，需能回溯到最近一个调仓日。
    ETF 域 _target_days 以 lookback=区间跨度/RECENT_OPEN_LOOKBACK_DAYS 复用本函数
    （C6-P3-7，2026-10-15 审查：trade_cal 回溯口径收敛，勿再建平行实现）。
    """
    end = dt.datetime.strptime(end_ymd, "%Y%m%d").date()
    start = end - dt.timedelta(days=lookback)
    cal = pro.trade_cal(exchange="SSE", start_date=start.strftime("%Y%m%d"), end_date=end_ymd)
    if cal is None or cal.empty:
        return []
    cal = cal[cal["is_open"] == 1]
    return sorted((str(c) for c in cal["cal_date"]), reverse=True)


def _daily_basic_spot(pro, day_ymd, source_id):
    """tushare daily_basic 全市场估值 → akshare 同构中文列（代码/名称/市盈率-动态/市净率/总市值）。

    目标日无 daily_basic 时沿最近开市日回溯；输出列与 akshare stock_zh_a_spot_em 同构，
    供 all_a_valuation 备源与 industry_universe 复用，field_mapping/snapshot/industry calc 无需区分来源。
    """
    df = pro.daily_basic(trade_date=day_ymd)
    if df is None or df.empty:
        day = next((d for d in _open_days_desc(pro, day_ymd, lookback=15) if d < day_ymd), None)
        if day is None:
            raise SourceError(f"{source_id}: 目标日 {day_ymd} 无 daily_basic 数据")
        df = pro.daily_basic(trade_date=day)
    if df is None or df.empty:
        raise SourceError(f"{source_id}: daily_basic 无数据")
    basic = pro.stock_basic(list_status="L", fields="ts_code,name")
    df = df.merge(basic, on="ts_code", how="inner")
    df = df[df["ts_code"].str.endswith((".SH", ".SZ"))]
    df = df[~df["name"].str.contains("ST|退", na=False)]
    df = df.copy()
    df["代码"] = df["ts_code"].str.split(".").str[0]
    df["名称"] = df["name"]
    df["市盈率-动态"] = df["pe_ttm"]
    df["市净率"] = df["pb"]
    df["总市值"] = df["total_mv"] * 10000  # tushare 万元 → 元（与 akshare 总市值口径一致）
    return df[["代码", "名称", "市盈率-动态", "市净率", "总市值"]]
