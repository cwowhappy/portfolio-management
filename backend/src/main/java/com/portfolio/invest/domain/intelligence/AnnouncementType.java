package com.portfolio.invest.domain.intelligence;

/**
 * 公告类型：决策 #24 十类 + OTHER 兜底。label 为推送卡片等展示位的中文名
 * （照 {@code domain.research.ResearchStage} 的 label 先例，单一真源）。
 */
public enum AnnouncementType {
    INCREASE_HOLD("股东增持"),
    DECREASE_HOLD("股东减持"),
    BUYBACK("股份回购"),
    PLACEMENT("定增配股"),
    RELATED_TRANSACTION("关联交易"),
    EARNINGS_FORECAST("业绩预告"),
    EARNINGS_FLASH("业绩快报"),
    PERIODIC_REPORT("定期报告"),
    EQUITY_INCENTIVE("股权激励"),
    DELISTING_RISK("退市风险"),
    OTHER("其他");

    private final String label;

    AnnouncementType(String label) { this.label = label; }

    public String label() { return label; }
}
