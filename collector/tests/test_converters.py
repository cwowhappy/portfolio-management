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
