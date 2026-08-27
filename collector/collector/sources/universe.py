import akshare as ak
import pandas as pd


def fetch_all_a_valuation() -> pd.DataFrame:
    """全 A 股实时估值（akshare 东财行情），归一化为英文列名。

    Returns:
        DataFrame(code, name, pe, pb, market_cap)
    """
    df = ak.stock_zh_a_spot_em()
    df = df.rename(
        columns={
            "代码": "code",
            "名称": "name",
            "市盈率-动态": "pe",
            "市净率": "pb",
            "总市值": "market_cap",
        }
    )
    for col in ("pe", "pb", "market_cap"):
        df[col] = pd.to_numeric(df[col], errors="coerce")
    return df[["code", "name", "pe", "pb", "market_cap"]]
