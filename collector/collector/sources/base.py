from abc import ABC, abstractmethod

import pandas as pd


class SourceError(Exception):
    """源侧异常（fetch/convert/validate 失败），触发换源降级。"""


class Source(ABC):
    source_id: str
    supports_range: bool = False
    # P0-5：DB→DB 源（fetch 内自行 UPDATE、返回空帧走 writer 0 行路径）在 fetch 结束时
    # 设置该属性上报实际影响行数；常规源保持 None（rows_written 已准确）。executor 侧 getattr 读取。
    last_affected_rows: int | None = None

    @abstractmethod
    def fetch(self, params: dict) -> pd.DataFrame:
        """返回原始数据。params 含运行时参数（start/end/date）。"""
