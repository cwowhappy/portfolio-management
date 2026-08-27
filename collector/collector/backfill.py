import datetime as dt

from collector.sources.index import fetch_index_valuation, INDEX_CODES
from collector.store.writer import upsert_index


def backfill_index_history(pro, conn, start: str, end: str) -> None:
    for code, name in INDEX_CODES.items():
        df = fetch_index_valuation(pro, code, start, end)
        for _, row in df.iterrows():
            upsert_index(conn, row["trading_day"], code, name,
                         row["pe"], row["pb"], row.get("dividend_yield"))
