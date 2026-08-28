import argparse


def build_parser():
    parser = argparse.ArgumentParser(prog="collector")
    sub = parser.add_subparsers(dest="command", required=True)

    p_run = sub.add_parser("run", help="手动触发一次增量采集")
    p_run.add_argument("task_code")
    p_run.add_argument("--force", action="store_true", help="跳过交易日历门控")
    p_run.add_argument("--date", help="交易日 YYYY-MM-DD（默认今天）")

    p_list = sub.add_parser("list", help="列任务")
    p_list.add_argument("--enabled-only", "-e", action="store_true")

    p_backfill = sub.add_parser("backfill", help="按区间回填历史")
    p_backfill.add_argument("task_code")
    p_backfill.add_argument("--start", required=True, help="YYYY-MM-DD")
    p_backfill.add_argument("--end", required=True, help="YYYY-MM-DD")
    p_backfill.add_argument("--force", action="store_true")

    p_seed = sub.add_parser("seed", help="YAML→DB 幂等同步")
    p_seed.add_argument("--dry-run", action="store_true")

    p_hist = sub.add_parser("history", help="查执行历史")
    p_hist.add_argument("task_code")
    p_hist.add_argument("--limit", type=int, default=20)

    return parser


if __name__ == "__main__":
    args = build_parser().parse_args()
    # 命令分派在 Task 13/15 数据任务落地后接入真实 TaskRunner/仓库；此处先解析参数。
    print(args)
