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
        INSERT INTO industry_valuation (trading_day, industry_code, industry_name, pe, pb)
        VALUES (%s, %s, %s, %s, %s)
        ON CONFLICT (trading_day, industry_code) DO UPDATE SET
          pe=EXCLUDED.pe, pb=EXCLUDED.pb
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
}

TABLE_COLUMNS = {
    "valuation_snapshot": ["trading_day", "pe_median", "pb_median", "net_breaker_count", "net_breaker_ratio"],
    "treasury_yield_curve": ["trading_day", "term", "yield"],
    "index_valuation_history": ["trading_day", "index_code", "index_name", "pe", "pb", "dividend_yield"],
    "industry_valuation": ["trading_day", "industry_code", "industry_name", "pe", "pb"],
    "shenwan_industry_mapping": ["stock_code", "stock_name", "industry_code", "industry_name"],
    "index_constituent": ["index_code", "stock_code", "stock_name", "weight"],
}


class Store:
    def upsert(self, conn, table: str, records: list[dict]) -> int:
        if not records:
            return 0
        cols = TABLE_COLUMNS[table]
        sql = UPSERT_SQL[table]
        rows = [tuple(r.get(c) for c in cols) for r in records]
        try:
            with conn.cursor() as cur:
                cur.executemany(sql, rows)
            conn.commit()
        except psycopg.Error as e:
            raise StoreError(f"DB 写入失败: {e}") from e
        return len(rows)
