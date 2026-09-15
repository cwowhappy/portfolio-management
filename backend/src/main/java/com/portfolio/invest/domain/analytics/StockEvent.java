package com.portfolio.invest.domain.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 买+/卖−/送股+ */
public record StockEvent(LocalDate date, String stockCode, BigDecimal qtyDelta) {}
