package com.portfolio.invest.domain.research;

/**
 * 策略文档两级状态（D13 覆盖式）：DRAFT 草稿可随时暂存（字段可空），
 * FINALIZED 定稿后只读，修订即回 DRAFT 直接覆盖、不留版本链。
 * 与 DB research_strategy_doc.state 列同一契约。
 */
public enum StrategyState {
    DRAFT,
    FINALIZED
}
