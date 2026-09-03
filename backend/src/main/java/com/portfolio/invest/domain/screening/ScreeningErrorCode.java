package com.portfolio.invest.domain.screening;

/** 价值筛选域错误码。 */
public final class ScreeningErrorCode {
    private ScreeningErrorCode() {}

    public static final String NO_CONDITION = "SCREENING_NO_CONDITION";
    public static final String INVALID_SORT = "SCREENING_INVALID_SORT";
    public static final String INVALID_LIMIT = "SCREENING_INVALID_LIMIT";
}
