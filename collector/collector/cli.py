import argparse
import sys

import psycopg

from collector.backfill import run_backfill
from collector.config import load
from collector.executor.executor import Executor, AllSourcesFailed, StoreError
from collector.executor.selector import SourceSelector
from collector.repositories.runs import RunRepository
from collector.repositories.tasks import TaskRepository
from collector.scheduler.jobs import (
    assemble_collector,
    build_registries,
    load_calendar,
    load_task_defs,
    seed_tasks,
)
from collector.scheduler.runner import TaskRunner
from collector.store.writer import Store


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


def main(argv=None):
    args = build_parser().parse_args(argv)
    config = load()
    registries = build_registries(config)

    with psycopg.connect(config.database_url) as conn:
        if args.command == "seed":
            defs = load_task_defs("tasks")
            if args.dry_run:
                for t in defs:
                    print(t["task_code"])
            else:
                seed_tasks(conn, defs)
                print(f"已同步 {len(defs)} 个任务定义")
            return

        if args.command == "list":
            rows = TaskRepository(conn).list_enabled()
            for row in rows:
                print(f"{row['task_code']}  enabled={row['enabled']}  schedule={row['schedule']}")
            return

        row = TaskRepository(conn).get(args.task_code)
        if row is None:
            print(f"任务不存在: {args.task_code}")
            sys.exit(1)

        if args.command == "history":
            runs = RunRepository(conn).list_runs(args.task_code, args.limit)
            if not runs:
                print(f"无运行记录: {args.task_code}")
            for r in runs:
                print(f"{r['started_at']}  {r['status']}  {r['source_used'] or '-'}  "
                      f"rows={r['rows_written']}  {r['message'] or ''}")
            return

        task = assemble_collector(row, registries)
        runner = TaskRunner(config.database_url, load_calendar(conn), Executor(SourceSelector(), Store()))
        try:
            if args.command == "run":
                params = {"date": args.date} if args.date else {}
                result = runner.run(task, params=params, force=args.force)
            else:  # backfill
                result = run_backfill(runner, task, args.start, args.end)
        except ValueError as e:
            print(f"参数错误: {e}")
            sys.exit(2)
        except (AllSourcesFailed, StoreError) as e:
            print(f"采集失败: {e}")
            sys.exit(1)
        print(result)


if __name__ == "__main__":
    main()
