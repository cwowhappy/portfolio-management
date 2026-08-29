package com.portfolio.invest.application.portfolio;

import java.math.BigDecimal;

public record PortfolioOverviewView(
        BigDecimal totalAssets,
        BigDecimal totalCost,
        BigDecimal totalPnl,
        BigDecimal todayPnl,
        BigDecimal cashTotal,
        BigDecimal totalCashDividend,
        int positionCount,
        int groupCount
) {}
