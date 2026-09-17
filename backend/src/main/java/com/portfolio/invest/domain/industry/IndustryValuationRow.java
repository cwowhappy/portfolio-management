package com.portfolio.invest.domain.industry;

import java.math.BigDecimal;

public record IndustryValuationRow(String industryCode, String industryName,
        BigDecimal pe, BigDecimal pb, BigDecimal roe, BigDecimal dividendYield) {}
