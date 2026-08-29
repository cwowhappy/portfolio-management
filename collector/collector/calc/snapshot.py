import statistics

from collector.calc.base import Calc


class SnapshotCalc(Calc):
    def compute(self, records):
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
            g = grouped.setdefault(key, {"industry_name": r.get("industry_name"), "pe": 0.0, "pb": 0.0, "cap": 0.0})
            cap = r["market_cap"]
            g["cap"] += cap
            g["pe"] += r["pe"] * cap
            if r.get("pb") is not None:
                g["pb"] += r["pb"] * cap
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
                }
            )
        return rows
