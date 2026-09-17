package com.portfolio.invest.domain.industry;

import java.math.BigDecimal;
import java.time.LocalDate;

public record IndustryValuationPoint(LocalDate tradingDay, String industryCode, BigDecimal pe, BigDecimal pb) {}
