#!/usr/bin/env python3
"""MS-09：5 年个股估值分段回填（半年一段 × 10 次调用 make collect-backfill）。

逐段容错（issue #39，2026-09-26 机制化）：单段失败不中止后续段——段级幂等已在
2026-09-17 实跑验证（按日区间 UPSERT），失败段结尾汇总并打印可直接复制重跑的命令，
整体以非零码退出提醒处置；勿因单段失败整体重放。

段区间与原 Makefile one-liner 完全一致：END_i = 今日 − 183×i，START_i = 今日 − 183×(i+1)。
仅用标准库；经 `make industry-stock-backfill` 从仓库根调用（cwd 即仓库根）。
"""
import datetime as dt
import subprocess
import sys

TASK = "stock_valuation_daily"
SEGMENTS = 10
DAYS_PER_SEGMENT = 183


def main() -> int:
    end = dt.date.today()
    failed: list[tuple[int, str, str]] = []
    for i in range(SEGMENTS):
        start = (end - dt.timedelta(days=DAYS_PER_SEGMENT * (i + 1))).isoformat()
        stop = (end - dt.timedelta(days=DAYS_PER_SEGMENT * i)).isoformat()
        print(f"[industry-stock-backfill] 段 {i + 1}/{SEGMENTS} {start}~{stop}", flush=True)
        result = subprocess.run(
            ["make", "collect-backfill", f"TASK={TASK}", f"START={start}", f"END={stop}"]
        )
        if result.returncode != 0:
            failed.append((i + 1, start, stop))
            print(
                f"[industry-stock-backfill] 段 {i + 1} 失败（returncode={result.returncode}），继续后续段",
                flush=True,
            )
    if failed:
        print(
            f"[industry-stock-backfill] {len(failed)}/{SEGMENTS} 段失败（段级幂等，单独重跑以下命令即可）：",
            file=sys.stderr,
        )
        for failure in failed:
            print(f"  make collect-backfill TASK={TASK} START={failure[1]} END={failure[2]}", file=sys.stderr)
        return 1
    print(f"[industry-stock-backfill] {SEGMENTS}/{SEGMENTS} 段全部成功", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
