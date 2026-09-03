import datetime as dt

from collector.sources.base import SourceError
from collector.validators.base import Validator

# type 规则支持的预期类型；None 不参与类型判定（由 not_null/required 负责）。
_TYPE_CHECKS = {
    "str": lambda v: isinstance(v, str),
    "int": lambda v: isinstance(v, int) and not isinstance(v, bool),
    "float": lambda v: isinstance(v, float),
    "numeric": lambda v: isinstance(v, (int, float)) and not isinstance(v, bool),
    "number": lambda v: isinstance(v, (int, float)) and not isinstance(v, bool),
    "bool": lambda v: isinstance(v, bool),
    "date": lambda v: isinstance(v, dt.date),
}


def _rule_value(rule, *keys):
    """从 rule 中按给定键名顺序取第一个存在的值（兼容不同 YAML 书写习惯）。"""
    for key in keys:
        if key in rule:
            return rule[key]
    return None


class RuleValidator(Validator):
    def __init__(self, rules):
        self.rules = rules

    def validate(self, records):
        issues = []
        for rule in self.rules:
            check, level = rule["check"], rule.get("level", "hard")
            if check == "min_rows":
                if len(records) < rule["value"]:
                    if level == "hard":
                        raise SourceError(f"行数 {len(records)} < {rule['value']}")
                    issues.append(f"min_rows: {len(records)} < {rule['value']}")
            elif check == "required":
                missing = [r for r in records if not r.get(rule["field"])]
                if missing:
                    if level == "hard":
                        raise SourceError(f"必填字段缺失: {rule['field']}")
                    issues.append(f"required: {rule['field']}")
                    records = [r for r in records if r.get(rule["field"])]
            elif check == "not_null":
                records, dropped = self._drop(
                    records, rule, level, "not_null", lambda r, field=rule["field"]: r.get(field) is not None
                )
                if dropped:
                    issues.append(f"not_null {rule['field']}: 剔除 {dropped} 行")
            elif check == "type":
                expected = _rule_value(rule, "type", "value")
                if expected not in _TYPE_CHECKS:
                    raise SourceError(f"未知类型约束: {expected}")
                records, dropped = self._drop(
                    records,
                    rule,
                    level,
                    "type",
                    lambda r, field=rule["field"], exp=expected: r.get(field) is None
                    or _TYPE_CHECKS[exp](r.get(field)),
                )
                if dropped:
                    issues.append(f"type {rule['field']}: 剔除 {dropped} 行")
            elif check == "range":
                kept, dropped = [], 0
                for r in records:
                    v = r.get(rule["field"])
                    if v is None or rule["min"] <= v <= rule["max"]:
                        kept.append(r)
                    else:
                        dropped += 1
                records = kept
                if dropped:
                    issues.append(f"range {rule['field']}: 剔除 {dropped} 行")
            elif check == "unique":
                fields = rule["field"]
                keys = fields if isinstance(fields, list) else [fields]
                seen, kept, dropped = set(), [], 0
                for r in records:
                    key = tuple(r.get(k) for k in keys)
                    if key in seen:
                        dropped += 1
                    else:
                        seen.add(key)
                        kept.append(r)
                if dropped:
                    if level == "hard":
                        raise SourceError(f"唯一约束冲突: {fields}")
                    issues.append(f"unique {fields}: 剔除 {dropped} 行")
                    records = kept
            elif check == "allowed_values":
                allowed = set(_rule_value(rule, "values", "value") or [])
                records, dropped = self._drop(
                    records,
                    rule,
                    level,
                    "allowed_values",
                    lambda r, field=rule["field"], vals=allowed: r.get(field) in vals,
                )
                if dropped:
                    issues.append(f"allowed_values {rule['field']}: 剔除 {dropped} 行")
            else:
                raise SourceError(f"未知校验规则: {check}")
        return records, issues

    @staticmethod
    def _drop(records, rule, level, name, keep_fn):
        """按 keep_fn 过滤记录；hard 失败抛 SourceError。返回 (保留记录, 剔除行数)。"""
        kept = [r for r in records if keep_fn(r)]
        dropped = len(records) - len(kept)
        if dropped and level == "hard":
            raise SourceError(f"{name} 校验失败: {rule['field']}")
        return kept, dropped
