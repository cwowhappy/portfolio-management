package com.portfolio.invest.domain.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.SortedMap;

/** 无风险利率读侧端口（treasury_yield_curve term='1Y'，百分数原值，V4 契约）。空表 → 空 map（夏普退化 rf=0）。 */
public interface RiskFreeRatePort {

    SortedMap<LocalDate, BigDecimal> oneYearSeries(LocalDate from, LocalDate to);
}
