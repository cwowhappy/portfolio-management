import psycopg

from collector.executor.executor import StoreError

UPSERT_SQL = {
    "valuation_snapshot": """
        INSERT INTO valuation_snapshot (trading_day, pe_median, pb_median, net_breaker_count, net_breaker_ratio)
        VALUES (%s, %s, %s, %s, %s)
        ON CONFLICT (trading_day) DO UPDATE SET
          pe_median=EXCLUDED.pe_median, pb_median=EXCLUDED.pb_median,
          net_breaker_count=EXCLUDED.net_breaker_count, net_breaker_ratio=EXCLUDED.net_breaker_ratio
    """,
    "treasury_yield_curve": """
        INSERT INTO treasury_yield_curve (trading_day, term, yield)
        VALUES (%s, %s, %s)
        ON CONFLICT (trading_day, term) DO UPDATE SET yield=EXCLUDED.yield
    """,
    "index_valuation_history": """
        INSERT INTO index_valuation_history (trading_day, index_code, index_name, pe, pb, dividend_yield)
        VALUES (%s, %s, %s, %s, %s, %s)
        ON CONFLICT (trading_day, index_code) DO UPDATE SET
          pe=EXCLUDED.pe, pb=EXCLUDED.pb, dividend_yield=EXCLUDED.dividend_yield
    """,
    "industry_valuation": """
        INSERT INTO industry_valuation (trading_day, industry_code, industry_name, pe, pb, roe, dividend_yield)
        VALUES (%s, %s, %s, %s, %s, %s, %s)
        ON CONFLICT (trading_day, industry_code) DO UPDATE SET
          pe=EXCLUDED.pe, pb=EXCLUDED.pb, roe=EXCLUDED.roe, dividend_yield=EXCLUDED.dividend_yield
    """,
    "shenwan_industry_mapping": """
        INSERT INTO shenwan_industry_mapping (stock_code, stock_name, industry_code, industry_name)
        VALUES (%s, %s, %s, %s)
        ON CONFLICT (stock_code) DO UPDATE SET
          industry_code=EXCLUDED.industry_code, industry_name=EXCLUDED.industry_name
    """,
    "index_constituent": """
        INSERT INTO index_constituent (index_code, stock_code, stock_name, weight)
        VALUES (%s, %s, %s, %s)
        ON CONFLICT (index_code, stock_code) DO UPDATE SET
          stock_name=EXCLUDED.stock_name, weight=EXCLUDED.weight
    """,
    "stock_valuation_daily": """
        INSERT INTO stock_valuation_daily (
            trading_day, stock_code, stock_name, pe_ttm, pb, dividend_yield, total_mv, circ_mv, turnover_rate
        )
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
        ON CONFLICT (trading_day, stock_code) DO UPDATE SET
          stock_name=EXCLUDED.stock_name, pe_ttm=EXCLUDED.pe_ttm, pb=EXCLUDED.pb,
          dividend_yield=EXCLUDED.dividend_yield, total_mv=EXCLUDED.total_mv,
          circ_mv=EXCLUDED.circ_mv, turnover_rate=EXCLUDED.turnover_rate
    """,
    "stock_financial": """
        INSERT INTO stock_financial (
            report_date, stock_code, roe, roa, gross_margin, debt_to_assets, current_ratio, revenue_yoy, netprofit_yoy
        )
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
        ON CONFLICT (report_date, stock_code) DO UPDATE SET
          roe=EXCLUDED.roe, roa=EXCLUDED.roa, gross_margin=EXCLUDED.gross_margin,
          debt_to_assets=EXCLUDED.debt_to_assets, current_ratio=EXCLUDED.current_ratio,
          revenue_yoy=EXCLUDED.revenue_yoy, netprofit_yoy=EXCLUDED.netprofit_yoy
    """,
}

TABLE_COLUMNS = {
    "valuation_snapshot": ["trading_day", "pe_median", "pb_median", "net_breaker_count", "net_breaker_ratio"],
    "treasury_yield_curve": ["trading_day", "term", "yield"],
    "index_valuation_history": ["trading_day", "index_code", "index_name", "pe", "pb", "dividend_yield"],
    "industry_valuation": ["trading_day", "industry_code", "industry_name", "pe", "pb", "roe", "dividend_yield"],
    "shenwan_industry_mapping": ["stock_code", "stock_name", "industry_code", "industry_name"],
    "index_constituent": ["index_code", "stock_code", "stock_name", "weight"],
    "stock_valuation_daily": [
        "trading_day",
        "stock_code",
        "stock_name",
        "pe_ttm",
        "pb",
        "dividend_yield",
        "total_mv",
        "circ_mv",
        "turnover_rate",
    ],
    "stock_financial": [
        "report_date",
        "stock_code",
        "roe",
        "roa",
        "gross_margin",
        "debt_to_assets",
        "current_ratio",
        "revenue_yoy",
        "netprofit_yoy",
    ],
}


class Store:
    def upsert(self, conn, table: str, records: list[dict]) -> int:
        if not records:
            return 0
        try:
            cols = TABLE_COLUMNS[table]
            sql = UPSERT_SQL[table]
        except KeyError as e:
            raise StoreError(f"未知目标表: {table}") from e
        rows = [tuple(r.get(c) for c in cols) for r in records]
        try:
            with conn.cursor() as cur:
                if table == "index_constituent":
                    # 成分股是「每半年全量快照」语义：先删后插，被调出指数的成员不再残留。
                    # 只删本次涉及的 index_code，不动其它指数；删与插在同一事务，失败即回滚。
                    index_codes = sorted({r.get("index_code") for r in records if r.get("index_code") is not None})
                    if index_codes:
                        cur.execute("DELETE FROM index_constituent WHERE index_code = ANY(%s)", (index_codes,))
                cur.executemany(sql, rows)
            conn.commit()
        except psycopg.Error as e:
            # 不回滚的话连接停留在 aborted transaction 状态，
            # 后续 failed run 落库与整任务重试都会失败。
            conn.rollback()
            raise StoreError(f"DB 写入失败: {e}") from e
        return len(rows)
