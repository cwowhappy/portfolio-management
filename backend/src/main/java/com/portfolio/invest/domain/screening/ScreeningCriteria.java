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
        String industryCode, String indexCode, String sortBy, SortDirection sortDirection, int limit
) {
    public static final Set<String> SORTABLE_FIELDS = Set.of(
            "pe_ttm", "pb", "dividend_yield", "roe", "roa", "gross_margin",
            "debt_to_assets", "current_ratio", "revenue_yoy", "netprofit_yoy",
            "total_mv", "turnover_rate");

    /** 指数成分股范围白名单：与 index_constituent.index_code 存储格式一致（6 位无后缀）。 */
    public static final Set<String> INDEX_WHITELIST = Set.of("000300", "000905");

    public ScreeningCriteria { // 紧凑构造器：白名单外指数码直接拒绝
        if (indexCode != null && !INDEX_WHITELIST.contains(indexCode)) {
            throw new ScreeningException(ScreeningErrorCode.INVALID_INDEX, "不支持的指数: " + indexCode);
        }
    }

    public boolean hasAnyCondition() {
        return peTtmMax != null || pbMax != null || dividendYieldMin != null
                || roeMin != null || roaMin != null || grossMarginMin != null
                || debtToAssetsMax != null || currentRatioMin != null
                || revenueYoyMin != null || netprofitYoyMin != null
                || totalMvMin != null || turnoverRateMin != null
                || industryCode != null || indexCode != null;
    }
}
