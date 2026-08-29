import pandas as pd

from collector.converters.base import Converter
from collector.sources.base import SourceError


def _coerce(value, typ):
    if value is None or (isinstance(value, float) and pd.isna(value)):
        return None
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
