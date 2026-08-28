import pandas as pd
import tushare as ts

INDEX_CODES = {
    "000016": "上证50",
    "000300": "沪深300",
    "000905": "中证500",
    "399006": "创业板指",
    "000688": "科创50",
}


def _ts_code(index_code: str) -> str:
    return index_code + (".SH" if index_code.startswith("0") else ".SZ")


def fetch_index_valuation(pro, index_code: str, start: str, end: str) -> pd.DataFrame:
    """指数历史估值（tushare index_dailybasic），归一化为英文列名。

    Args:
        pro: tushare pro 客户端。
        index_code: 6 位指数代码，如 "000300"。
        start/end: 区间，格式 YYYYMMDD。

    Returns:
        DataFrame(trading_day, pe, pb, dividend_yield)
    """
    df = pro.index_dailybasic(
        ts_code=_ts_code(index_code), start_date=start, end_date=end
    )
    df = df.rename(columns={"trade_date": "trading_day"})
    for col in ("pe", "pb"):
        df[col] = pd.to_numeric(df[col], errors="coerce")
    # index_dailybasic 不提供股息率（无 dv_ratio 字段）；置 None 以保持契约列可空。
    # 指数股息率（沪深300 股息率 → ERP）需另接数据源（中证指数/akshare 乐咕乐股）。
    df["dividend_yield"] = None
    return df[["trading_day", "pe", "pb", "dividend_yield"]]
