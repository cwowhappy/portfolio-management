import pandas as pd

from collector.converters.base import Converter
from collector.sources.base import SourceError


def _coerce(value, typ):
    if value is None:
        return None
    # pd.isna 通判 NaN/NA/NaT；但对 list 等容器返回数组，布尔判定有歧义，先排除。
    if not isinstance(value, (list, tuple, dict, set)):
        try:
            if pd.isna(value):
                return None
        except (TypeError, ValueError):
            pass
    if typ == "str":
        return str(value)
    if typ == "numeric":
        try:
            return float(value)
        except (TypeError, ValueError):
            return None
    if typ == "int":
        try:
            return int(value)
        except (TypeError, ValueError):
            return None
    return value


class FieldMappingConverter(Converter):
    def __init__(self, columns: dict):
        self.columns = columns

    def convert(self, raw: pd.DataFrame) -> list[dict]:
        records = []
        for _, row in raw.iterrows():
            rec = {}
            for out, spec in self.columns.items():
                src_col = spec.get("from")
                if src_col not in raw.columns:
                    if "default" in spec:
                        rec[out] = spec["default"]
                    elif spec.get("required"):
                        raise SourceError(f"必填列缺失: {src_col}")
                    else:
                        rec[out] = None
                else:
                    rec[out] = _coerce(row[src_col], spec.get("type", "str"))
            records.append(rec)
        return records
