package com.portfolio.invest.domain.valuation;

import java.math.BigDecimal;
import java.time.LocalDate;

public record IndustryValuation(
        LocalDate tradingDay,
        String industryCode,
        String industryName,
        BigDecimal pe,
        BigDecimal pb,
        BigDecimal roe,
        BigDecimal dividendYield
) {}
