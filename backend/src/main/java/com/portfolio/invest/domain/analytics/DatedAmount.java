package com.portfolio.invest.domain.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 投资者视角现金流：出资为 −、回收为 +。 */
public record DatedAmount(LocalDate date, BigDecimal amount) {}
