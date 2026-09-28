package com.portfolio.invest.domain.research;

/**
 * 复盘回流状态（F16 用户确认后入库，不自动回流）：PENDING 创建态；
 * CONFIRMED 预留两步确认（用户已确认、wiki 待写入）；REFLOWN 已回流并记 wiki_entry_id
 * （二次确认幂等返回既有条目，不重复建）。
 * 与 DB research_review.reflux_state 列同一契约。
 */
public enum RefluxState {
    PENDING,
    CONFIRMED,
    REFLOWN
}
