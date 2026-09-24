package com.portfolio.invest.domain.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;

/** TWR 净值指数点位（首日=1），spec 02-设计规格 §2.1。 */
public record DatedIndex(LocalDate date, BigDecimal index) {}
