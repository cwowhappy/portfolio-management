import akshare as ak
import pandas as pd

from collector.sources.base import Source, SourceError


class ConfigurableSource(Source):
    def __init__(self, source_id, kind, call, params=None, pro_factory=None):
        self.source_id = source_id
        self.kind = kind
        self.call = call
        self.params = params or {}
        self.pro_factory = pro_factory
        self.supports_range = kind in ("tushare", "http")  # 声明区间能力，实现期可按需覆盖
        if kind not in ("akshare", "tushare", "http"):
            raise SourceError(f"未知源类型: {kind}")

    def fetch(self, params: dict) -> pd.DataFrame:
        merged = {**self.params, **params}
        if self.kind == "akshare":
            fn = getattr(ak, self.call, None)
        elif self.kind == "tushare":
            if self.pro_factory is None:
                raise SourceError("tushare 源需要 pro_factory")
            fn = getattr(self.pro_factory(), self.call, None)
        elif self.kind == "http":
            raise SourceError("http 源需自定义实现（本期无直接 http 源）")
        else:
            raise SourceError(f"未知源类型: {self.kind}")
        if fn is None:
            raise SourceError(f"源 {self.source_id} 找不到调用 {self.call}")
        result = fn(**merged)
        return result if isinstance(result, pd.DataFrame) else pd.DataFrame(result)
