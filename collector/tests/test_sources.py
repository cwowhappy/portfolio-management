import pandas as pd
import pytest

import collector.sources.configurable as cfg


def _akshare_stub(*a, **kw):
    return pd.DataFrame({"代码": ["600519"], "名称": ["贵州茅台"]})


def test_configurable_akshare_source_calls_function(monkeypatch):
    monkeypatch.setattr(cfg.ak, "stock_zh_a_spot_em", _akshare_stub)
    src = cfg.ConfigurableSource("ak_spot", "akshare", "stock_zh_a_spot_em")
    df = src.fetch({})
    assert "代码" in df.columns


def test_configurable_tushare_source_uses_pro_factory():
    calls = {}

    def fake_pro_factory():
        class FakePro:
            def index_dailybasic(self, **kw):
                calls.update(kw)
                return pd.DataFrame({"trade_date": ["20260828"], "pe": [12.0]})

        return FakePro()

    src = cfg.ConfigurableSource("ts_idx", "tushare", "index_dailybasic", pro_factory=fake_pro_factory)
    df = src.fetch({"ts_code": "000300.SH", "start_date": "20260828", "end_date": "20260828"})
    assert calls["ts_code"] == "000300.SH"
    assert "pe" in df.columns


def test_unknown_kind_raises():
    from collector.sources.base import SourceError

    with pytest.raises(SourceError):
        cfg.ConfigurableSource("x", "nope", "fn")


# ---------------------------------------------------------------- fetch 分发失败分支


def test_tushare_without_pro_factory_raises():
    from collector.sources.base import SourceError

    src = cfg.ConfigurableSource("ts_idx", "tushare", "index_dailybasic")
    with pytest.raises(SourceError, match="pro_factory"):
        src.fetch({})


def test_http_kind_raises_placeholder_error():
    """http 源是占位类型，fetch 必须报「需自定义实现」而非静默执行。"""
    from collector.sources.base import SourceError

    src = cfg.ConfigurableSource("h", "http", "anything")
    with pytest.raises(SourceError, match="http 源需自定义实现"):
        src.fetch({})


def test_missing_call_raises_not_found():
    """akshare 上不存在指定函数时应报「找不到调用」。"""
    from collector.sources.base import SourceError

    src = cfg.ConfigurableSource("ak_spot", "akshare", "no_such_function")
    with pytest.raises(SourceError, match="找不到调用 no_such_function"):
        src.fetch({})


def test_fetch_unknown_kind_raises_defensive():
    """构造后 kind 被篡改时 fetch 的防御分支仍应报未知源类型。"""
    from collector.sources.base import SourceError

    src = cfg.ConfigurableSource("ak_spot", "akshare", "stock_zh_a_spot_em")
    src.kind = "weird"
    with pytest.raises(SourceError, match="未知源类型"):
        src.fetch({})
