package com.portfolio.invest.application.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 走势图序列（F04）：组合 totalValue 绝对值日序列 + 基准收盘序列（截齐到组合窗口，归一化留前端）。 */
public record NavSeriesView(
        LocalDate windowStart,
        LocalDate windowEnd,
        List<NavPoint> points,
        Map<String, List<IndexPoint>> benchmarks) {

    public record NavPoint(LocalDate date, BigDecimal totalValue) {}

    public record IndexPoint(LocalDate date, BigDecimal close) {}
}
