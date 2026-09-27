"""P1-8 etf_basic enrich 可观测 + 间隔可配置。

已确认方案 a：失败计数上报（last_warnings → run.message，含失败数/总数/样例码）；
enrich 间隔默认常量模块 ETF_ENRICH_INTERVAL，env COLLECTOR_ETF_DETAIL_INTERVAL 可覆盖。
不做并发（对非官方移动端接口保持礼貌限速）。
"""

from unittest.mock import MagicMock

import pandas as pd

from collector.sources.constants import ETF_ENRICH_INTERVAL
from collector.sources.plugins import EtfBasicSource


def _catalog(codes_names):
    return pd.DataFrame({"代码": [c for c, _ in codes_names], "名称": [n for _, n in codes_names]})


def _source(detail_side_effects, sleeps, interval=None):
    detail = MagicMock(side_effect=detail_side_effects)
    return EtfBasicSource(
        "etf_basic",
        catalog_fetch=lambda: _catalog(CODES),
        detail_fetch=detail,
        sleep_fn=sleeps.append,
        interval=interval,
    )


CODES = [("510300", "沪深300ETF"), ("512880", "银行ETF"), ("511990", "华宝添益")]


def test_enrich_failures_reported_in_last_warnings():
    sleeps = []
    src = _source([{"SHORTNAME": "沪深300ETF"}, RuntimeError("boom"), {"SHORTNAME": "华宝添益"}], sleeps)
    df = src.fetch({})
    assert len(df) == 3  # 失败行保留（既有契约）
    assert src.last_warnings == ["enrich 失败 1/3，样例：512880"]


def test_enrich_all_ok_no_warning():
    sleeps = []
    src = _source([{"SHORTNAME": "a"}, {"SHORTNAME": "b"}, {"SHORTNAME": "c"}], sleeps)
    src.fetch({})
    assert not src.last_warnings


def test_last_warnings_reset_per_fetch():
    """源是共享单例：第二次干净运行不应残留第一次的告警。"""
    sleeps = []
    src = _source([RuntimeError("x"), {"SHORTNAME": "b"}, {"SHORTNAME": "c"}], sleeps)
    src.fetch({})
    assert src.last_warnings
    src.detail_fetch = MagicMock(return_value={"SHORTNAME": "ok"})
    src.fetch({})
    assert not src.last_warnings


def test_sample_codes_capped_in_warning():
    sleeps = []
    codes = [(f"51{i:04d}", f"基金{i}") for i in range(12)]
    src = EtfBasicSource(
        "etf_basic",
        catalog_fetch=lambda: _catalog(codes),
        detail_fetch=MagicMock(side_effect=RuntimeError("boom")),
        sleep_fn=sleeps.append,
    )
    src.fetch({})
    warn = src.last_warnings[0]
    assert "12/12" in warn
    assert warn.count("51") <= 6  # 样例码截断（≤5 个 + 前缀计数），防 1685 只全量刷屏


def test_interval_defaults_to_constant():
    sleeps = []
    src = _source([{"SHORTNAME": "a"}, {"SHORTNAME": "b"}, {"SHORTNAME": "c"}], sleeps)
    src.fetch({})
    assert sleeps == [ETF_ENRICH_INTERVAL] * 3


def test_interval_env_override(monkeypatch):
    monkeypatch.setenv("COLLECTOR_ETF_DETAIL_INTERVAL", "0.05")
    sleeps = []
    src = EtfBasicSource(
        "etf_basic",
        catalog_fetch=lambda: _catalog([("510300", "x")]),
        detail_fetch=MagicMock(return_value={"SHORTNAME": "a"}),
        sleep_fn=sleeps.append,
    )
    src.fetch({})
    assert sleeps == [0.05]
