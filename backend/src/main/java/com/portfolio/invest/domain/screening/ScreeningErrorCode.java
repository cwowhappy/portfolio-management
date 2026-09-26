package com.portfolio.invest.domain.screening;

/** 价值筛选域错误码。 */
public final class ScreeningErrorCode {
    private ScreeningErrorCode() {}

    public static final String NO_CONDITION = "SCREENING_NO_CONDITION";
    public static final String INVALID_SORT = "SCREENING_INVALID_SORT";
    public static final String INVALID_LIMIT = "SCREENING_INVALID_LIMIT";
    public static final String INVALID_INDEX = "SCREENING_INVALID_INDEX";
    public static final String INVALID_CATEGORY = "SCREENING_INVALID_CATEGORY";
    public static final String WATCHLIST_LIMIT_EXCEEDED = "SCREENING_WATCHLIST_LIMIT_EXCEEDED";
    public static final String INVALID_STOCK = "SCREENING_INVALID_STOCK";
}
