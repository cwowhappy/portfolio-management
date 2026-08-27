package com.portfolio.invest.domain.valuation;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TreasuryYield(LocalDate tradingDay, BigDecimal yield10y) {}
