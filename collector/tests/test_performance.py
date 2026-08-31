"""性能 smoke：5k 行全 A 行情的 convert + executemany 批量写路径。

阈值是「防误改回归的宽下限」而非性能基准：防的是 iterrows 被误改成
逐格 itertuples/at 访问、executemany 被误改成逐行 execute 这类数量级劣化。
阈值刻意放宽到 CI 抖动不会误报。
"""

import time
from unittest.mock import MagicMock

import pandas as pd

from collector.converters.field_mapping import FieldMappingConverter
from collector.store.writer import TABLE_COLUMNS, Store

ROW_COUNT = 5000
# 实测为亚秒级；10s 只在写法发生数量级劣化时触发。
TIME_BUDGET_SECONDS = 10.0

# 与 collector/scheduler/jobs.py 的 field_mapping_all_a 一致的全 A 行情映射
ALL_A_COLUMNS = {
    "code": {"from": "代码", "type": "str"},
    "name": {"from": "名称", "type": "str"},
    "pe": {"from": "市盈率-动态", "type": "numeric"},
    "pb": {"from": "市净率", "type": "numeric"},
    "market_cap": {"from": "总市值", "type": "numeric"},
}


def _all_a_df(n):
    return pd.DataFrame(
        {
            "代码": [f"{i:06d}" for i in range(n)],
            "名称": [f"股票{i}" for i in range(n)],
            "市盈率-动态": [10.0 + i % 50 for i in range(n)],
            "市净率": [1.0 + i % 10 for i in range(n)],
            "总市值": [1e9 + i * 1e6 for i in range(n)],
        }
    )


def test_convert_and_executemany_5k_rows_within_budget():
    conv = FieldMappingConverter(ALL_A_COLUMNS)
    store = Store()
    conn = MagicMock()
    cur = conn.cursor.return_value.__enter__.return_value

    start = time.perf_counter()
    records = conv.convert(_all_a_df(ROW_COUNT))
    # writer 只验批量调用形态：mock 连接走真实 executemany 参数构造。
    # 真实链路里 valuation_snapshot 写的是 calc 聚合后的记录，这里借其表定义驱动 writer 路径。
    written = store.upsert(conn, "valuation_snapshot", records)
    elapsed = time.perf_counter() - start

    assert len(records) == ROW_COUNT
    assert records[0] == {"code": "000000", "name": "股票0", "pe": 10.0, "pb": 1.0, "market_cap": 1e9}
    assert written == ROW_COUNT
    cur.executemany.assert_called_once()
    rows = cur.executemany.call_args.args[1]
    assert len(rows) == ROW_COUNT
    assert all(isinstance(r, tuple) and len(r) == len(TABLE_COLUMNS["valuation_snapshot"]) for r in rows)
    conn.commit.assert_called_once()
    assert elapsed < TIME_BUDGET_SECONDS, f"5k 行 convert+upsert 耗时 {elapsed:.2f}s，超出防回归下限"
