import pandas as pd


def _median_or_none(s: pd.Series):
    """中位数，空序列/NaN 时返回 None，避免 psycopg 绑定 NaN。"""
    return None if s.empty else float(s.median())


def compute_snapshot(universe: pd.DataFrame) -> dict:
    pe = universe["pe"]
    valid = pe[(pe > 0) & (pe <= 100)]
    pb = universe["pb"].dropna()
    total = int(universe["pb"].notna().sum())
    net_breaker_count = int((universe["pb"] < 1).sum())
    return {
        "pe_median": _median_or_none(valid),
        "pb_median": _median_or_none(pb),
        "net_breaker_count": net_breaker_count,
        "net_breaker_ratio": round(net_breaker_count / total, 4) if total else 0.0,
    }


def compute_industry_valuation(universe: pd.DataFrame, mapping: pd.DataFrame) -> list[dict]:
    df = universe.merge(mapping, on="code", how="inner")
    df = df[df["pe"] > 0]  # 剔除亏损股（PE 不可比）
    rows = []
    for industry_code, g in df.groupby("industry_code"):
        if not g["pb"].notna().any():  # PB 全缺失的行业跳过，避免写入 NaN
            continue
        cap = g["market_cap"].dropna()
        if cap.sum() <= 0:
            continue
        pe_w = float((g["pe"] * cap).sum() / cap.sum())
        pb_w = float((g["pb"] * cap).sum() / cap.sum())
        rows.append({
            "industry_code": industry_code,
            "industry_name": g["industry_name"].iloc[0],
            "pe": round(pe_w, 4),
            "pb": round(pb_w, 4),
        })
    return rows
