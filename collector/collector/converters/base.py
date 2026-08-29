from abc import ABC, abstractmethod

import pandas as pd


class Converter(ABC):
    @abstractmethod
    def convert(self, raw: pd.DataFrame) -> list[dict]:
        """原始数据 → 规范记录（键对齐目标表列，类型确定）。"""
