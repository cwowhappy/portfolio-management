from abc import ABC, abstractmethod


class Calc(ABC):
    @abstractmethod
    def compute(self, records: list[dict]) -> list[dict]:
        """规范记录 → 聚合后的规范记录。"""
