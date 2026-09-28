package com.portfolio.invest.domain.research;

/**
 * 复盘档位三档（D7 澄清 #13：月主模板默认 + 季深度归因 + 周简版兜底）——
 * 月粒度匹配 A 股持仓复盘节奏，季粒度适合深度归因，周粒度过密以简版兜底。
 * 与 DB research_review.tier 列同一契约；字段集差异由 answers JSONB 弹性承载（MS-24 收敛）。
 */
public enum ReviewTier {
    MONTHLY("月度复盘"),
    QUARTERLY("季度复盘"),
    WEEKLY("周度复盘");

    private final String label;

    ReviewTier(String label) { this.label = label; }

    public String label() { return label; }
}
