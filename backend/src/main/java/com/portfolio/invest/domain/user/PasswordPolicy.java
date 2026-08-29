package com.portfolio.invest.domain.user;

/** 密码策略：≥8 位且同时含字母与数字。 */
public final class PasswordPolicy {

    private PasswordPolicy() {}

    public static void validate(String password) {
        if (password == null || password.length() < 8) {
            throw new UserException(UserErrorCode.WEAK_PASSWORD, "密码至少8位且包含字母和数字");
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw new UserException(UserErrorCode.WEAK_PASSWORD, "密码至少8位且包含字母和数字");
        }
    }
}
