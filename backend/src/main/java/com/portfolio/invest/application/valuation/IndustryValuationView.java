package com.portfolio.invest.application.valuation;

import java.math.BigDecimal;

public record IndustryValuationView(
        String industryCode,
        String industryName,
        BigDecimal pe,
        BigDecimal pb,
        BigDecimal roe,
        BigDecimal dividendYield
) {}
