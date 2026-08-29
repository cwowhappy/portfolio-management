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
