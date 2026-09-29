package com.portfolio.invest.domain.research;

/**
 * 投研 SOP 四阶段（D4 灵活流转：可任意顺序进入/回退，不硬锁线性状态机）。
 * 与前端展示、SKILL.md 引导语同一契约（NFR-1 单一真源）。
 */
public enum ResearchStage {
    NEW_ANALYSIS("新分析"),
    STRATEGY("制定投资策略"),
    POSITION("建仓与持仓"),
    REVIEW("复盘");

    private final String label;

    ResearchStage(String label) { this.label = label; }

    public String label() { return label; }
}
