import pytest

from collector.cli import build_parser


def test_backfill_requires_range():
    parser = build_parser()
    with pytest.raises(SystemExit):
        parser.parse_args(["backfill", "t"])  # 缺 --start/--end


def test_run_parses_date_and_force():
    parser = build_parser()
    args = parser.parse_args(["run", "t", "--date", "2026-08-28", "--force"])
    assert args.task_code == "t"
    assert args.date == "2026-08-28"
    assert args.force is True
