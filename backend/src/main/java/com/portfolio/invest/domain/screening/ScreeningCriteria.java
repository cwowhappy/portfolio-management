package com.portfolio.invest.domain.screening;

import java.math.BigDecimal;
import java.util.Set;

/** 筛选条件：五维 AND 组合，null 字段不参与筛选。 */
public record ScreeningCriteria(
        BigDecimal peTtmMax, BigDecimal pbMax, BigDecimal dividendYieldMin,
        BigDecimal roeMin, BigDecimal roaMin, BigDecimal grossMarginMin,
        BigDecimal debtToAssetsMax, BigDecimal currentRatioMin,
        BigDecimal revenueYoyMin, BigDecimal netprofitYoyMin,
        BigDecimal totalMvMin, BigDecimal turnoverRateMin,
        String industryCode, String sortBy, SortDirection sortDirection, int limit
) {
    public static final Set<String> SORTABLE_FIELDS = Set.of(
            "pe_ttm", "pb", "dividend_yield", "roe", "roa", "gross_margin",
            "debt_to_assets", "current_ratio", "revenue_yoy", "netprofit_yoy",
            "total_mv", "turnover_rate");

    public boolean hasAnyCondition() {
        return peTtmMax != null || pbMax != null || dividendYieldMin != null
                || roeMin != null || roaMin != null || grossMarginMin != null
                || debtToAssetsMax != null || currentRatioMin != null
                || revenueYoyMin != null || netprofitYoyMin != null
                || totalMvMin != null || turnoverRateMin != null
                || industryCode != null;
    }
}
