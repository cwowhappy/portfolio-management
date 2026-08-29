import pandas as pd
import pytest

from collector.converters.field_mapping import FieldMappingConverter
from collector.sources.base import SourceError


def test_field_mapping_renames_and_casts():
    conv = FieldMappingConverter({
        "code": {"from": "代码", "type": "str"},
        "pe": {"from": "市盈率-动态", "type": "numeric"},
    })
    raw = pd.DataFrame({"代码": ["600519"], "市盈率-动态": ["25.5"]})
    records = conv.convert(raw)
    assert records == [{"code": "600519", "pe": 25.5}]


def test_field_mapping_default_and_missing_column():
    conv = FieldMappingConverter({
        "code": {"from": "代码", "type": "str"},
        "pe": {"from": "市盈率-动态", "type": "numeric", "default": 0},
    })
    raw = pd.DataFrame({"代码": ["600519"]})
    records = conv.convert(raw)
    assert records == [{"code": "600519", "pe": 0}]


def test_required_missing_raises():
    conv = FieldMappingConverter({"code": {"from": "代码", "type": "str", "required": True}})
    with pytest.raises(SourceError):
        conv.convert(pd.DataFrame({"名称": ["x"]}))
