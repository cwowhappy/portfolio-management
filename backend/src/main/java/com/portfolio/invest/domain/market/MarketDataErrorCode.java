package com.portfolio.invest.domain.market;

/** 行情域错误码（B2）：抛点、GlobalExceptionHandler、测试同引此处常量，不上 enum（switch 按字符串）。 */
public final class MarketDataErrorCode {

    public static final String INVALID_CODE = "INVALID_CODE";
    public static final String INVALID_PERIOD = "INVALID_PERIOD";
    public static final String INVALID_QUERY = "INVALID_QUERY";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String UPSTREAM_UNAVAILABLE = "UPSTREAM_UNAVAILABLE";
    public static final String BAD_RESPONSE = "BAD_RESPONSE";

    private MarketDataErrorCode() {}
}
