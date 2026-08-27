package com.portfolio.invest.application.valuation;

import com.portfolio.invest.domain.valuation.ValuationSnapshot;

import java.math.BigDecimal;
import java.util.List;

/** 仪表盘总览视图：最新快照 + 各指标分位 + ERP + 温度计。 */
public record ValuationOverviewView(
        ValuationSnapshot latestSnapshot,
        BigDecimal pePercentile,
        BigDecimal pbPercentile,
        BigDecimal netBreakerPercentile,
        BigDecimal erp,
        BigDecimal erpPercentile,
        BigDecimal thermometer,
        List<IndexValuationView> indices,
        boolean dataAccumulating
) {
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
