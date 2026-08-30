package com.portfolio.invest.domain.portfolio;

/** 持仓组合域错误码。 */
public final class PortfolioErrorCode {
    private PortfolioErrorCode() {}

    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String SELL_EXCEEDS_QUANTITY = "SELL_EXCEEDS_QUANTITY";
    public static final String GROUP_NOT_EMPTY = "GROUP_NOT_EMPTY";
    public static final String INVALID_GROUP_TYPE = "INVALID_GROUP_TYPE";
    public static final String INVALID_INPUT = "INVALID_INPUT";
}
