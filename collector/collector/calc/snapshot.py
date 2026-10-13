import statistics

from collector.calc.base import Calc


class SnapshotCalc(Calc):
    def compute(self, records):
        # PE 中位数口径（C6 注记，2026-10-12 审查）：0 < pe ≤ 100——剔除亏损股（pe≤0）
        # 与极端高 PE（>100，炒作/壳价值股），使中位数代表「正常可投资区间」的市场估值
        # 水平。出处：market-valuation P1-估值数据底座 SRS 决策（「全 A PE/PB 中位数
        # 剔除 PE≤0 与 PE>100」）；与行业加权口径同向（IndustryValuationCalc 亦跳过 pe<=0）。
        pe_valid = [r["pe"] for r in records if r.get("pe") and 0 < r["pe"] <= 100]
        pb = [r["pb"] for r in records if r.get("pb") is not None]
        net_breaker = sum(1 for r in records if r.get("pb") is not None and r["pb"] < 1)
        total = len(pb)
        return [
            {
                "pe_median": statistics.median(pe_valid) if pe_valid else None,
                "pb_median": statistics.median(pb) if pb else None,
                "net_breaker_count": net_breaker,
                "net_breaker_ratio": round(net_breaker / total, 4) if total else 0.0,
            }
        ]


class IndustryValuationCalc(Calc):
    def compute(self, records):
        grouped = {}
        for r in records:
            if r.get("pe") is None or r["pe"] <= 0 or r.get("market_cap") is None:
                continue
            key = r["industry_code"]
            g = grouped.setdefault(
                key,
                {
                    "industry_name": r.get("industry_name"),
                    "pe": 0.0,
                    "pb": 0.0,
                    "roe": 0.0,
                    "div": 0.0,
                    "cap": 0.0,
                    "roe_cap": 0.0,
                    "div_cap": 0.0,
                },
            )
            cap = r["market_cap"]
            g["cap"] += cap
            g["pe"] += r["pe"] * cap
            if r.get("pb") is not None:
                g["pb"] += r["pb"] * cap
            if r.get("roe") is not None:
                g["roe"] += r["roe"] * cap
                g["roe_cap"] += cap
            if r.get("dividend_yield") is not None:
                g["div"] += r["dividend_yield"] * cap
                g["div_cap"] += cap
        rows = []
        for code, g in grouped.items():
            if g["cap"] <= 0:
                continue
            rows.append(
                {
                    "industry_code": code,
                    "industry_name": g["industry_name"],
                    "pe": round(g["pe"] / g["cap"], 4),
                    "pb": round(g["pb"] / g["cap"], 4),
                    "roe": round(g["roe"] / g["roe_cap"], 4) if g["roe_cap"] > 0 else None,
                    "dividend_yield": round(g["div"] / g["div_cap"], 4) if g["div_cap"] > 0 else None,
                }
            )
        return rows
