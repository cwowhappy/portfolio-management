import pandas as pd
import tushare as ts


def fetch_shenwan_industries(pro) -> pd.DataFrame:
    """申万一级行业列表（SW2021）。

    Returns:
        DataFrame(index_code, industry_name)
    """
    return pro.index_classify(level="L1", src="SW2021")[["index_code", "industry_name"]]


def fetch_shenwan_mapping(pro) -> pd.DataFrame:
    """个股 → 行业名映射（stock_basic 的 industry 字段）。

    Returns:
        DataFrame(code, industry_name)
    """
    df = pro.stock_basic(exchange="", list_status="L", fields="ts_code,name,industry")
    df = df.rename(columns={"ts_code": "code", "industry": "industry_name"})
    df["code"] = df["code"].str.split(".").str[0]
    return df[["code", "industry_name"]]
