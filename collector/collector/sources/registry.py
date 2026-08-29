import tushare as ts

from collector.sources.base import SourceError
from collector.sources.configurable import ConfigurableSource


class SourceRegistry:
    def __init__(self, tushare_token=None, plugins=None):
        self.tushare_token = tushare_token
        self.plugins = plugins or {}

    def get(self, spec: dict):
        kind = spec.get("type")
        if kind == "plugin":
            name = spec.get("class")
            if name not in self.plugins:
                raise SourceError(f"未注册的源插件: {name}")
            return self.plugins[name]
        if kind in ("akshare", "tushare"):
            pro_factory = None
            if kind == "tushare":
                token = self.tushare_token

                def pro_factory(token=token):
                    return ts.pro_api(token)

            return ConfigurableSource(
                spec.get("source_id"),
                kind,
                spec.get("call"),
                params=spec.get("params"),
                pro_factory=pro_factory,
            )
        raise SourceError(f"未知源类型: {kind}")
