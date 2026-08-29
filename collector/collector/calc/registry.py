from collector.sources.base import SourceError


class CalcRegistry:
    def __init__(self, plugins=None):
        self.plugins = plugins or {}

    def get(self, name):
        if name in self.plugins:
            return self.plugins[name]
        raise SourceError(f"未注册的计算: {name}")
