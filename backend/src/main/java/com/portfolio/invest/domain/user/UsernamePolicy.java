package com.portfolio.invest.domain.user;

/** 用户名策略：非空且 trim 后 ≤64 字符。 */
public final class UsernamePolicy {

    private UsernamePolicy() {}

    public static void validate(String username) {
        if (username == null || username.isBlank()) {
            throw new UserException(UserErrorCode.INVALID_USERNAME, "用户名不能为空");
        }
        if (username.trim().length() > 64) {
            throw new UserException(UserErrorCode.INVALID_USERNAME, "用户名最长64个字符");
        }
    }
}
