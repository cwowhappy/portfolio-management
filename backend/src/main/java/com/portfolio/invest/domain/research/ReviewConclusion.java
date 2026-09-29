package com.portfolio.invest.domain.research;

/**
 * 证伪评审结论（M16-F15，规格四值逐字）：维持 / 减仓 / 退出 / 修订策略。
 * 与 DB research_falsifier_review.conclusion 取值逐字一致；label 为 journal
 * 事件「证伪评审：&lt;结论&gt;」的文案源。REVISE 不隐式改策略状态（Review Focus 3：
 * 落库与策略修订分离，由响应 suggestStrategyRevise 提示位引导前端显式 revise）。
 */
public enum ReviewConclusion {
    HOLD("维持"),
    REDUCE("减仓"),
    EXIT("退出"),
    REVISE("修订策略");

    private final String label;

    ReviewConclusion(String label) { this.label = label; }

    public String label() { return label; }
}
