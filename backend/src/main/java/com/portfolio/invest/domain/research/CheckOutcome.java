package com.portfolio.invest.domain.research;

/**
 * 检查项三态结论（D5 软提醒）：PASS 未越线 / HIT 越线命中 / UNSET 未设定。
 * UNSET 为中性——规则缺失（未设定规则）或证伪条件待核对，既非通过也非失败（F10「未设定」中性结论）。
 */
public enum CheckOutcome {
    PASS("通过"),
    HIT("命中"),
    UNSET("未设定");

    private final String label;

    CheckOutcome(String label) { this.label = label; }

    public String label() { return label; }
}
