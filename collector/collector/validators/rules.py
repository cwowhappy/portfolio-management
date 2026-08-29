from collector.sources.base import SourceError
from collector.validators.base import Validator


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
        return records, issues
