package com.portfolio.invest.application.valuation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 仪表盘总览视图：最新快照 + 各指标分位 + ERP + 温度计（应用层视图，不直接暴露 domain 对象）。 */
public record ValuationOverviewView(
        SnapshotView latestSnapshot,
        BigDecimal pePercentile,
        BigDecimal pbPercentile,
        BigDecimal netBreakerPercentile,
        BigDecimal erp,
        BigDecimal erpPercentile,
        BigDecimal thermometer,
        List<IndexValuationView> indices,
        boolean dataAccumulating
) {
    /** 最新快照的线格式视图（A1：避免把 domain.ValuationSnapshot 直接作 @ResponseBody）。 */
    public record SnapshotView(
            LocalDate tradingDay,
            BigDecimal peMedian,
            BigDecimal pbMedian,
            int netBreakerCount,
            BigDecimal netBreakerRatio
    ) {}

    public record IndexValuationView(
            String indexCode,
            String indexName,
            BigDecimal pe,
            BigDecimal pb,
            BigDecimal dividendYield,
            BigDecimal pePercentile,
            BigDecimal pbPercentile
    ) {}
}
