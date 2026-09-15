package com.portfolio.invest.domain.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 正=净转入（仅 DEPOSIT−WITHDRAW） */
public record ExternalFlow(LocalDate date, BigDecimal amount) {}
