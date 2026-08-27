import akshare as ak


def _find_date_column(df) -> str | None:
    for col in ("date", "日期", "trade_date"):
        if col in df.columns:
            return col
    return None


def fetch_treasury_10y() -> float:
    """中国 10 年期国债收益率（最新值）。

    akshare bond_zh_us_rate 返回日期倒序（最新在前），但为避免依赖其隐式排序，
    这里按日期列显式降序后再取首个非空值（iloc[0]）。
    """
    df = ak.bond_zh_us_rate()
    date_col = _find_date_column(df)
    if date_col is not None:
        df = df.sort_values(date_col, ascending=False)
    return float(df["中国国债收益率10年"].dropna().iloc[0])
