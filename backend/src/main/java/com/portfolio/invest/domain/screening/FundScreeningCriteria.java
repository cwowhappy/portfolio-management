package com.portfolio.invest.domain.screening;

import java.math.BigDecimal;
import java.util.Set;

/** ETF 筛选条件：四维 AND 组合，null 字段不参与筛选。 */
public record FundScreeningCriteria(
        BigDecimal feeRateMax, BigDecimal scaleMin, BigDecimal trackingErrorMax,
        String category, String sortBy, SortDirection sortDirection, int limit
) {
    public static final Set<String> SORTABLE_FIELDS = Set.of("fee_rate", "scale", "tracking_error_1y");

    /** 类别白名单：与 etf_basic.category 六桶存储一致。 */
    public static final Set<String> CATEGORIES = Set.of("宽基", "行业", "商品", "债券", "QDII", "其他");

    public FundScreeningCriteria { // 紧凑构造器：白名单外类别直接拒绝
        if (category != null && !CATEGORIES.contains(category)) {
            throw new ScreeningException(ScreeningErrorCode.INVALID_CATEGORY, "不支持的类别: " + category);
        }
    }

    public boolean hasAnyCondition() {
        return feeRateMax != null || scaleMin != null || trackingErrorMax != null || category != null;
    }
}
