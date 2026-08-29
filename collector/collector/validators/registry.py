from collector.sources.base import SourceError


class ValidatorRegistry:
    def __init__(self, plugins=None):
        self.plugins = plugins or {}

    def get(self, name_or_rules):
        if isinstance(name_or_rules, list):
            from collector.validators.rules import RuleValidator
            return RuleValidator(name_or_rules)
        if name_or_rules in self.plugins:
            return self.plugins[name_or_rules]
        raise SourceError(f"未注册的校验器: {name_or_rules}")
