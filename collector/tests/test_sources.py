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

    src = cfg.ConfigurableSource("ts_idx", "tushare", "index_dailybasic",
                                 pro_factory=fake_pro_factory)
    df = src.fetch({"ts_code": "000300.SH", "start_date": "20260828", "end_date": "20260828"})
    assert calls["ts_code"] == "000300.SH"
    assert "pe" in df.columns


def test_unknown_kind_raises():
    from collector.sources.base import SourceError
    with pytest.raises(SourceError):
        cfg.ConfigurableSource("x", "nope", "fn")
