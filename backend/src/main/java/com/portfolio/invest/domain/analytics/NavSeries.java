package com.portfolio.invest.domain.analytics;

import java.util.List;

/** points 按 tradeDate 升序，不可变 */
public record NavSeries(List<DailyPoint> points) {}
