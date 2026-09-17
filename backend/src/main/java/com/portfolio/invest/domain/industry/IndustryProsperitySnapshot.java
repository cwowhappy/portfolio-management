package com.portfolio.invest.domain.industry;

import java.math.BigDecimal;

public record IndustryProsperitySnapshot(String industryCode, BigDecimal roeDeltaMedian,
        BigDecimal revenueYoyMedian, long sampleSize) {}
