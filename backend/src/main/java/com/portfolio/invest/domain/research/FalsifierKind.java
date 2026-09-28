package com.portfolio.invest.domain.research;

/**
 * 证伪条件类型（D10）：PREDICATE 谓词类由 P3 FalsifierEvaluator 自动求值，
 * EVENT 事件类靠人工勾选（event_checked），不自动命中。
 * 与 DB research_falsifier.kind 列同一契约。
 */
public enum FalsifierKind {
    PREDICATE,
    EVENT
}
