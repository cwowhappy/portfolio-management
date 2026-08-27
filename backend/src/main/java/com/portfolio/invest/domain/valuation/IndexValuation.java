package com.portfolio.invest.domain.valuation;

import java.math.BigDecimal;
import java.time.LocalDate;

public record IndexValuation(
        LocalDate tradingDay,
        String indexCode,
        String indexName,
        BigDecimal pe,
        BigDecimal pb,
        BigDecimal dividendYield
) {}
