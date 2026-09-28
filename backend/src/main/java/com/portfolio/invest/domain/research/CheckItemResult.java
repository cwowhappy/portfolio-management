package com.portfolio.invest.domain.research;

import java.math.BigDecimal;

/**
 * 单条检查项结果（S4 JSONB 快照行，纯数据载体）。规则项含 threshold/currentValue；
 * F01 必查项（布尔语境）与证伪核对项（待核对语境）threshold/currentValue 为 null。
 */
public record CheckItemResult(String metric, BigDecimal threshold, BigDecimal currentValue,
                              CheckOutcome outcome) {
}
