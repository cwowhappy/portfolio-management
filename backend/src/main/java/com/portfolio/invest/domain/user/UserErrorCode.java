package com.portfolio.invest.domain.user;

/** 用户域错误码（B2）：抛点、GlobalExceptionHandler、测试同引此处常量，不上 enum（switch 按字符串）。 */
public final class UserErrorCode {

    public static final String INVALID_USERNAME = "INVALID_USERNAME";
    public static final String USERNAME_TAKEN = "USERNAME_TAKEN";
    public static final String WEAK_PASSWORD = "WEAK_PASSWORD";
    public static final String USER_NOT_FOUND = "USER_NOT_FOUND";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String INVALID_STATE = "INVALID_STATE";

    private UserErrorCode() {}
}
