from abc import ABC, abstractmethod

import pandas as pd


class SourceError(Exception):
    """源侧异常（fetch/convert/validate 失败），触发换源降级。"""


class Source(ABC):
    source_id: str
    supports_range: bool = False

    @abstractmethod
    def fetch(self, params: dict) -> pd.DataFrame:
        """返回原始数据。params 含运行时参数（start/end/date）。"""
