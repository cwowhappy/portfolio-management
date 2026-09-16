package com.portfolio.invest.domain.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DailyPoint(LocalDate tradeDate, BigDecimal marketValue, BigDecimal cashBalance, BigDecimal totalValue) {}
