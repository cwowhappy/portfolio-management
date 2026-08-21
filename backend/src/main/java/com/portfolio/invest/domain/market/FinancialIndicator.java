package com.portfolio.invest.domain.market;

/** 单期财务指标。 */
public record FinancialIndicator(
        String reportDate,
        Double eps,
        Double bps,
        Double totalRevenue,
        Double netProfit,
        Double weightedRoe,
        Double grossMargin) {}
