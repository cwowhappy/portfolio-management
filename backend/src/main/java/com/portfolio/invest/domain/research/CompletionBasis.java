package com.portfolio.invest.domain.research;

/**
 * 完成方式依据（D22 角标）：AUTO = 必备产物齐套自动标记（D16），MANUAL = 用户手动标记兜底，
 * PENDING = 未完成（尚无完成依据）。与 {@link StageCompletionService.ManualState}（手动覆盖输入）是两套枚举。
 */
public enum CompletionBasis {
    PENDING("待定"),
    AUTO("自动"),
    MANUAL("手动");

    private final String label;

    CompletionBasis(String label) { this.label = label; }

    public String label() { return label; }
}
