package com.portfolio.invest.market.dto;

/** 单期财务指标。 */
public record FinancialIndicator(
        String reportDate,
        Double eps,
        Double bps,
        Double totalRevenue,
        Double netProfit,
        Double weightedRoe,
        Double grossMargin) {}
