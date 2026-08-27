import datetime as dt
from unittest.mock import MagicMock

import pandas as pd

from collector.backfill import backfill_index_history
from collector.sources.index import INDEX_CODES


def test_backfill_writes_all_indices():
    pro = MagicMock()
    pro.index_dailybasic.return_value = pd.DataFrame({
        "trade_date": ["20260827"], "pe": [12.8], "pb": [1.42], "dv_ratio": [2.35],
    })
    conn = MagicMock()
    backfill_index_history(pro, conn, "20160827", "20260827")
    assert conn.cursor.return_value.__enter__.return_value.execute.call_count == len(INDEX_CODES)
