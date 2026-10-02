package com.portfolio.invest.domain.intelligence;

/**
 * 订阅标的（intelligence_subscription_stock 行，F16）：去重键为 stockCode
 * （子表复合主键 (user_id, stock_code) 的标的侧），stockName 仅展示用、改名走整体替换。
 *
 * @param stockCode  标的代码（如 600519）
 * @param stockName  标的名称（可空——源站缺名时容许）
 */
public record SubscriptionStock(String stockCode, String stockName) {
}
