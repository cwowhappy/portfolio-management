package com.portfolio.invest.domain.valuation;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ValuationSnapshot(
        LocalDate tradingDay,
        BigDecimal peMedian,
        BigDecimal pbMedian,
        int netBreakerCount,
        BigDecimal netBreakerRatio
) {}
