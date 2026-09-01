package com.portfolio.invest.domain.screening;

import java.math.BigDecimal;

public record StockScreeningResult(
        String stockCode, String stockName, String industryCode, String industryName,
        BigDecimal peTtm, BigDecimal pb, BigDecimal dividendYield,
        BigDecimal roe, BigDecimal roa, BigDecimal grossMargin,
        BigDecimal debtToAssets, BigDecimal currentRatio,
        BigDecimal revenueYoy, BigDecimal netprofitYoy,
        BigDecimal totalMv, BigDecimal turnoverRate
) {}
