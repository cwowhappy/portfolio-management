import akshare as ak


def fetch_treasury_10y() -> float:
    """中国 10 年期国债收益率（最新值）。

    注意：akshare bond_zh_us_rate 返回按日期倒序（最新在前），
    因此取首个非空值（iloc[0]），而非末行。
    """
    df = ak.bond_zh_us_rate()
    return float(df["中国国债收益率10年"].dropna().iloc[0])
