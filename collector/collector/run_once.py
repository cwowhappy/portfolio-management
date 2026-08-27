import datetime as dt

import psycopg
import tushare as ts

from collector.calc.snapshot import compute_industry_valuation, compute_snapshot
from collector.config import load
from collector.sources.industry import fetch_shenwan_mapping
from collector.sources.treasury import fetch_treasury_10y
from collector.sources.universe import fetch_all_a_valuation
from collector.store.writer import (
    upsert_industry,
    upsert_mapping,
    upsert_snapshot,
    upsert_treasury,
)


def collect_once(conn, config) -> None:
    """编排一次完整采集：抓取 → 计算 → 落库（当日快照）。

    不含指数回填（backfill_index_history 是独立的一次性任务，非每日采集）。
    """
    today = dt.date.today()

    # 1) 全 A 估值 + 市场快照
    universe = fetch_all_a_valuation()
    snapshot = compute_snapshot(universe)

    # 2) 申万行业映射 + 行业估值（依赖 tushare）
    pro = ts.pro_api(config.tushare_token)
    mapping = fetch_shenwan_mapping(pro)
    industry_rows = compute_industry_valuation(universe, mapping)

    # 3) 国债收益率
    treasury_yield = fetch_treasury_10y()

    # 4) 行业映射落库：mapping 无 name 列，stock_name 从 universe 取（缺失回退 code）；
    #    industry_code 暂以 industry_name 兜底（Task 3 输出无独立 industry_code）。
    name_by_code = dict(zip(universe["code"], universe["name"]))
    mapping_rows = [
        (r.code, name_by_code.get(r.code, r.code), r.industry_name, r.industry_name)
        for r in mapping.itertuples()
    ]

    # 5) 落库
    upsert_snapshot(conn, today, snapshot)
    upsert_treasury(conn, today, treasury_yield)
    upsert_industry(conn, today, industry_rows)
    upsert_mapping(conn, mapping_rows)


if __name__ == "__main__":
    config = load()
    with psycopg.connect(config.database_url) as conn:
        collect_once(conn, config)
        print("采集完成")
