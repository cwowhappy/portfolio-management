import pandas as pd
import pytest

from collector.converters.field_mapping import FieldMappingConverter
from collector.sources.base import SourceError


def test_field_mapping_renames_and_casts():
    conv = FieldMappingConverter(
        {
            "code": {"from": "代码", "type": "str"},
            "pe": {"from": "市盈率-动态", "type": "numeric"},
        }
    )
    raw = pd.DataFrame({"代码": ["600519"], "市盈率-动态": ["25.5"]})
    records = conv.convert(raw)
    assert records == [{"code": "600519", "pe": 25.5}]


def test_field_mapping_default_and_missing_column():
    conv = FieldMappingConverter(
        {
            "code": {"from": "代码", "type": "str"},
            "pe": {"from": "市盈率-动态", "type": "numeric", "default": 0},
        }
    )
    raw = pd.DataFrame({"代码": ["600519"]})
    records = conv.convert(raw)
    assert records == [{"code": "600519", "pe": 0}]


def test_required_missing_raises():
    conv = FieldMappingConverter({"code": {"from": "代码", "type": "str", "required": True}})
    with pytest.raises(SourceError):
        conv.convert(pd.DataFrame({"名称": ["x"]}))


# ---------------------------------------------------------------- _coerce 空值通判


def test_coerce_pandas_missing_values_become_none():
    conv = FieldMappingConverter({"v": {"from": "x", "type": "numeric"}, "s": {"from": "x", "type": "str"}})
    raw = pd.DataFrame({"x": [pd.NA, pd.NaT, float("nan"), "1.5"]})
    records = conv.convert(raw)
    assert records[0] == {"v": None, "s": None}
    assert records[1] == {"v": None, "s": None}
    assert records[2] == {"v": None, "s": None}
    assert records[3] == {"v": 1.5, "s": "1.5"}


def test_coerce_list_value_not_misjudged():
    """pd.isna 对 list 返回数组（布尔歧义），容器值应原样放行而非报错。"""
    conv = FieldMappingConverter({"v": {"from": "x", "type": "other"}})
    raw = pd.DataFrame({"x": [[["a", "b"]]]})
    assert conv.convert(raw) == [{"v": [["a", "b"]]}]


# ---------------------------------------------------------------- _coerce 脏数据路径


def test_coerce_none_passthrough():
    """value 为 None 时直通返回 None，不再走 isna 与类型转换。"""
    from collector.converters.field_mapping import _coerce

    assert _coerce(None, "str") is None
    assert _coerce(None, "numeric") is None
    assert _coerce(None, "int") is None


def test_coerce_isna_ambiguity_falls_back():
    """pd.isna 对 ndarray 返回数组（布尔判定抛 ValueError），应兜底放行原值。"""
    import numpy as np

    from collector.converters.field_mapping import _coerce

    arr = np.array([1, 2])
    assert _coerce(arr, "other") is arr


def test_coerce_numeric_dirty_string_returns_none():
    """东财 '-' 占位符或畸形数值字符串转 float 失败时应为 None，不抛异常。"""
    conv = FieldMappingConverter({"pe": {"from": "市盈率-动态", "type": "numeric"}})
    raw = pd.DataFrame({"市盈率-动态": ["-", "abc", "25.5"]})
    records = conv.convert(raw)
    assert records == [{"pe": None}, {"pe": None}, {"pe": 25.5}]


def test_coerce_int_casts_and_dirty_value_returns_none():
    """int 类型：合法字符串/浮点可转；'-' 等脏值转换失败返回 None。"""
    conv = FieldMappingConverter({"n": {"from": "x", "type": "int"}})
    raw = pd.DataFrame({"x": ["5", 7.9, "-"]})
    records = conv.convert(raw)
    assert records[0]["n"] == 5
    assert records[1]["n"] == 7
    assert records[2]["n"] is None


def test_optional_column_missing_without_default_becomes_none():
    """可选列缺失且无 default 时填 None（不抛错、不用默认值）。"""
    conv = FieldMappingConverter(
        {
            "code": {"from": "代码", "type": "str"},
            "pe": {"from": "市盈率-动态", "type": "numeric"},
        }
    )
    raw = pd.DataFrame({"代码": ["600519"]})
    assert conv.convert(raw) == [{"code": "600519", "pe": None}]
