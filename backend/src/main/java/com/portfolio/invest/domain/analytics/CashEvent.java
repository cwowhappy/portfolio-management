package com.portfolio.invest.domain.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 转入+/转出−/卖出净入+/买入净出−/现金股息+ */
public record CashEvent(LocalDate date, BigDecimal amountDelta) {}
