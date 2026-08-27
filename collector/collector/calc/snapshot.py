import pandas as pd


def compute_snapshot(universe: pd.DataFrame) -> dict:
    pe = universe["pe"]
    valid = pe[(pe > 0) & (pe <= 100)]
    pb = universe["pb"].dropna()
    total = len(universe)
    net_breaker_count = int((universe["pb"] < 1).sum())
    return {
        "pe_median": float(valid.median()),
        "pb_median": float(pb.median()),
        "net_breaker_count": net_breaker_count,
        "net_breaker_ratio": round(net_breaker_count / total, 4) if total else 0.0,
    }


def compute_industry_valuation(universe: pd.DataFrame, mapping: pd.DataFrame) -> list[dict]:
    df = universe.merge(mapping, on="code", how="inner")
    df = df[df["pe"] > 0]  # 剔除亏损股（PE 不可比）
    rows = []
    for industry, g in df.groupby("industry_name"):
        cap = g["market_cap"].dropna()
        if cap.sum() <= 0:
            continue
        pe_w = float((g["pe"] * cap).sum() / cap.sum())
        pb_w = float((g["pb"] * cap).sum() / cap.sum())
        rows.append({"industry_name": industry, "pe": round(pe_w, 4), "pb": round(pb_w, 4)})
    return rows
