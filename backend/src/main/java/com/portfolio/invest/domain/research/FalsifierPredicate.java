package com.portfolio.invest.domain.research;

/**
 * 证伪谓词维度（D10）：P3 {@code FalsifierEvaluator} 消费，
 * 取值与 DB research_falsifier.predicate 列逐字一致。
 */
public enum FalsifierPredicate {
    PRICE_BELOW,
    PRICE_ABOVE,
    PE_ABOVE,
    PB_ABOVE
}
