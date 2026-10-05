package com.portfolio.invest.application.portfolio;

import java.math.BigDecimal;

/** 删除持仓影响预检：将被级联删除的交易/分红笔数与随之从统计中消失的已实现盈亏。 */
public record DeleteImpactView(Long tradeCount, Long dividendCount, BigDecimal realizedPnl) {
}
