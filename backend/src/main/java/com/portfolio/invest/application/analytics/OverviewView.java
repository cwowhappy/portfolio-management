package com.portfolio.invest.application.analytics;

import java.math.BigDecimal;
import java.util.Map;

/** 收益总览卡（F01~F03）：数值为绝对值口径（归一化留前端），round 4 位。 */
public record OverviewView(
        BigDecimal totalValue,
        BigDecimal twrCumulative,
        BigDecimal twrAnnualized,
        BigDecimal irr /* nullable：无现金流/无解时为 null，前端标「无现金流，退化口径」 */,
        long windowDays,
        Map<String, BenchmarkComparison> benchmarks) {

    /** 单只基准同期表现；excess = 组合 TWR − 基准 TWR（基准窗口短于组合窗口时为截齐口径）。 */
    public record BenchmarkComparison(String indexCode, String indexName, BigDecimal twr, BigDecimal excess) {}
}
