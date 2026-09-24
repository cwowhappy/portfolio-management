package com.portfolio.invest.domain.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 带日期的单期收益率（夏普与归因共用）。 */
public record DatedReturn(LocalDate date, BigDecimal ret) {}
