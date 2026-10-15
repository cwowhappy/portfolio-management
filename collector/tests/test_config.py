import pytest

from collector.config import Config, load


def test_load_missing_env_friendly_error(monkeypatch):
    monkeypatch.setattr("collector.config.load_dotenv", lambda: None)
    monkeypatch.delenv("DATABASE_URL", raising=False)
    monkeypatch.delenv("TUSHARE_TOKEN", raising=False)
    with pytest.raises(SystemExit, match="DATABASE_URL"):
        load()


def test_load_ok(monkeypatch):
    monkeypatch.setattr("collector.config.load_dotenv", lambda: None)
    monkeypatch.setenv("DATABASE_URL", "postgresql://x")
    monkeypatch.setenv("TUSHARE_TOKEN", "tok")
    assert load() == Config(database_url="postgresql://x", tushare_token="tok")


def test_load_db_only_allows_missing_tushare_token(monkeypatch):
    """C3（规范）：只读 DB 命令声明 required=("database_url",) 时不强制数据源密钥，
    缺失的 tushare_token 回退空串（该命令路径不实例化数据源）。"""
    monkeypatch.setattr("collector.config.load_dotenv", lambda: None)
    monkeypatch.setenv("DATABASE_URL", "postgresql://x")
    monkeypatch.delenv("TUSHARE_TOKEN", raising=False)
    assert load(required=("database_url",)) == Config(database_url="postgresql://x", tushare_token="")


def test_load_db_only_missing_database_url_still_fails(monkeypatch):
    """C3：database_url 是所有命令的必需键，只读命令也不例外。"""
    monkeypatch.setattr("collector.config.load_dotenv", lambda: None)
    monkeypatch.delenv("DATABASE_URL", raising=False)
    monkeypatch.setenv("TUSHARE_TOKEN", "tok")
    with pytest.raises(SystemExit, match="DATABASE_URL"):
        load(required=("database_url",))


def test_load_hint_appended_to_missing_env_error(monkeypatch):
    """C6-P3-9（2026-10-15 审查）：hint 附加命令级语境（如 run/backfill 装配全部数据源）。"""
    monkeypatch.setattr("collector.config.load_dotenv", lambda: None)
    monkeypatch.delenv("DATABASE_URL", raising=False)
    monkeypatch.delenv("TUSHARE_TOKEN", raising=False)
    with pytest.raises(SystemExit, match="装配全部数据源") as exc:
        load(hint="run/backfill 装配全部数据源，故需全部数据源密钥")
    assert "DATABASE_URL" in str(exc.value)


def test_load_default_no_hint_unchanged(monkeypatch):
    monkeypatch.setattr("collector.config.load_dotenv", lambda: None)
    monkeypatch.delenv("DATABASE_URL", raising=False)
    monkeypatch.delenv("TUSHARE_TOKEN", raising=False)
    with pytest.raises(SystemExit) as exc:
        load()
    assert "装配全部数据源" not in str(exc.value)
