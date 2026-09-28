package com.portfolio.invest.domain.research;

/**
 * 检查单结论（F10 留痕）：CONFIRMED 逐项确认 / OVERRIDDEN 越过命中项继续（必填理由）。
 * 与 DB research_check_record.result 取值逐字一致。
 */
public enum CheckResult {
    CONFIRMED("确认"),
    OVERRIDDEN("越过");

    private final String label;

    CheckResult(String label) { this.label = label; }

    public String label() { return label; }
}
