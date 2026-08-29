from abc import ABC, abstractmethod


class Validator(ABC):
    @abstractmethod
    def validate(self, records: list[dict]) -> tuple[list[dict], list[str]]:
        """返回 (通过/清洗后的记录, 问题列表)。hard 失败抛 SourceError。"""
