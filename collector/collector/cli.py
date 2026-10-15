import argparse
import sys

import psycopg

from collector.backfill import run_backfill
from collector.config import DB_CONNECT_TIMEOUT_SECONDS, load
from collector.executor.executor import AllSourcesFailed, Executor, StoreError
from collector.executor.selector import SourceSelector
from collector.repositories.backfill import BackfillProgressRepository
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
from collector.sources.http_guard import install_default_http_timeout
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


# C3（规范）：按命令分级配置校验——每命令声明所需键；list/history/seed 等只读 DB
# 命令不强制数据源密钥（不实例化数据源注册表），缺配置报错由 config.load 统一列出缺失项。
COMMAND_REQUIRED_KEYS = {
    "run": ("database_url", "tushare_token"),
    "backfill": ("database_url", "tushare_token"),
    "seed": ("database_url",),
    "list": ("database_url",),
    "history": ("database_url",),
}
# C6-P3-9（2026-10-15 审查）：run/backfill 对纯 akshare/HTTP 任务也强制 token——
# 保守可接受（装配全部数据源），报错文案注明语境，避免用户误判为过度强制。
FULL_ASSEMBLY_HINT = "run/backfill 装配全部数据源，故需全部数据源密钥"


def main(argv=None):
    # H1/L3：入口安装 requests 缺省超时（akshare 内部无超时调用点获得上界；幂等，重复安装不叠加）
    install_default_http_timeout()
    args = build_parser().parse_args(argv)
    config = load(
        required=COMMAND_REQUIRED_KEYS[args.command],
        hint=FULL_ASSEMBLY_HINT if args.command in ("run", "backfill") else None,
    )
    registries = None  # 仅 run/backfill 需要（下方惰性构建）；只读命令不实例化数据源

    with psycopg.connect(config.database_url, connect_timeout=DB_CONNECT_TIMEOUT_SECONDS) as conn:
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
            task_repo = TaskRepository(conn)
            rows = task_repo.list_enabled() if args.enabled_only else task_repo.list_all()
            latest = RunRepository(conn).latest_run_status()
            for row in rows:
                started, status = latest.get(row["task_code"], (None, None))
                last_run = f"{started:%Y-%m-%d %H:%M} {status}" if started else "-"
                print(f"{row['task_code']}  enabled={row['enabled']}  schedule={row['schedule']}  last_run={last_run}")
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
                affected = f"  affected={r['rows_affected']}" if r.get("rows_affected") is not None else ""
                print(
                    f"{r['started_at']}  {r['status']}  {r['source_used'] or '-'}  "
                    f"rows={r['rows_written']}{affected}  {r['message'] or ''}"
                )
            return

        registries = build_registries(config)
        task = assemble_collector(row, registries)
        runner = TaskRunner(config.database_url, load_calendar(conn), Executor(SourceSelector(), Store()))
        try:
            if args.command == "run":
                params = {"date": args.date} if args.date else {}
                result = runner.run(task, params=params, force=args.force)
            else:  # backfill
                result = run_backfill(runner, task, args.start, args.end, progress=BackfillProgressRepository(conn))
        except ValueError as e:
            print(f"参数错误: {e}")
            sys.exit(2)
        except (AllSourcesFailed, StoreError) as e:
            print(f"采集失败: {e}")
            sys.exit(1)
        print(result)


if __name__ == "__main__":
    main()
