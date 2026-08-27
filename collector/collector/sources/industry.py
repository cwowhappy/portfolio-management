import pandas as pd
import tushare as ts


def fetch_shenwan_industries(pro) -> pd.DataFrame:
    """申万一级行业列表（SW2021）。

    Returns:
        DataFrame(index_code, industry_name)
    """
    return pro.index_classify(level="L1", src="SW2021")[["index_code", "industry_name"]]


def fetch_shenwan_mapping(pro) -> pd.DataFrame:
    """个股 → 申万一级行业名映射。

    先取申万一级行业列表（SW2021），再对每个一级行业调 index_member_all(l1_code=...)
    获取其成分股。index_member_all 默认 is_new="Y"（仅当前成分），返回的 l1_name
    即申万一级行业名（注意：不能用 stock_basic 的 industry 字段，那是证监会行业，
    非申万）。

    Returns:
        DataFrame(code, industry_name)
    """
    industries = pro.index_classify(level="L1", src="SW2021")
    frames = []
    for code in industries["index_code"]:
        members = pro.index_member_all(l1_code=code)
        frames.append(members[["ts_code", "l1_name"]])
    result = pd.concat(frames, ignore_index=True)
    result = result.rename(columns={"ts_code": "code", "l1_name": "industry_name"})
    result["code"] = result["code"].str.split(".").str[0]
    return result[["code", "industry_name"]]
