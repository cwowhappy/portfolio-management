import datetime as dt
import logging

import psycopg
import tushare as ts

from collector.calc.snapshot import compute_industry_valuation, compute_snapshot
from collector.config import load
from collector.sources.index import INDEX_CODES, fetch_index_valuation
from collector.sources.industry import fetch_shenwan_mapping
from collector.sources.treasury import fetch_treasury_10y
from collector.sources.universe import fetch_all_a_valuation
from collector.store.writer import (
    upsert_index,
    upsert_industry,
    upsert_mapping,
    upsert_snapshot,
    upsert_treasury,
)

logger = logging.getLogger(__name__)


def collect_once(conn, config) -> None:
    """编排一次完整采集：抓取 → 计算 → 落库（当日快照）。

    免费数据（akshare：快照 + 国债）先落库；tushare 依赖段（行业估值 + 每日指数）
    失败时降级，不影响已持久化的快照/国债。
    不含指数历史回填（backfill_index_history 是独立的一次性任务，非每日采集）。
    """
    today = dt.date.today()

    # 1) 全 A 估值 + 市场快照（仅依赖 akshare）
    universe = fetch_all_a_valuation()
    snapshot = compute_snapshot(universe)

    # 2) 国债收益率（仅依赖 akshare）
    treasury_yield = fetch_treasury_10y()

    # 3) 免费数据先行落库：快照 + 国债
    upsert_snapshot(conn, today, snapshot)
    upsert_treasury(conn, today, treasury_yield)

    # 4) tushare 依赖段：每日指数估值 + 申万行业映射/行业估值。
    #    任一步失败（鉴权/限流/权限）都只记录日志并降级，不回滚已落库的快照/国债。
    try:
        pro = ts.pro_api(config.tushare_token)

        # 4a) 每日指数估值（FR-A4）：五个指数当日估值，仅更新当日行。
        today_str = today.strftime("%Y%m%d")
        for code, name in INDEX_CODES.items():
            for _, row in fetch_index_valuation(pro, code, today_str, today_str).iterrows():
                upsert_index(conn, row["trading_day"], code, name,
                             row["pe"], row["pb"], row.get("dividend_yield"))

        # 4b) 申万行业映射 + 行业估值
        mapping = fetch_shenwan_mapping(pro)
        industry_rows = compute_industry_valuation(universe, mapping)

        # 4c) 行业映射落库：mapping 无 name 列，stock_name 从 universe 取（缺失回退 code）；
        #     industry_code 用真实申万一级代码（缺失才回退 industry_name）。
        name_by_code = dict(zip(universe["code"], universe["name"]))
        mapping_rows = [
            (
                r.code,
                name_by_code.get(r.code, r.code),
                r.industry_code if isinstance(r.industry_code, str) and r.industry_code else r.industry_name,
                r.industry_name,
            )
            for r in mapping.itertuples()
        ]

        upsert_industry(conn, today, industry_rows)
        upsert_mapping(conn, mapping_rows)
    except Exception:
        logger.exception("tushare 段失败，降级为仅快照+国债")


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    config = load()
    with psycopg.connect(config.database_url) as conn:
        collect_once(conn, config)
        print("采集完成")
