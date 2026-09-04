package com.portfolio.invest.domain.user;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    @DisplayName("合法密码通过")
    @Test
    void validPasswordsPass() {
        assertThatCode(() -> PasswordPolicy.validate("abc12345")).doesNotThrowAnyException();
        assertThatCode(() -> PasswordPolicy.validate("Abcd1234!")).doesNotThrowAnyException();
    }

    @DisplayName("空密码抛异常")
    @Test
    void blankPasswordThrows() {
        assertThatThrownBy(() -> PasswordPolicy.validate(null))
                .isInstanceOf(UserException.class).hasMessageContaining("至少8位");
    }

    @DisplayName("不足8位或缺少字母数字抛异常")
    @Test
    void tooShortOrMissingLetterDigitThrows() {
        assertThatThrownBy(() -> PasswordPolicy.validate("short1"))
                .isInstanceOf(UserException.class).hasMessageContaining("至少8位");
        assertThatThrownBy(() -> PasswordPolicy.validate("12345678"))
                .isInstanceOf(UserException.class).hasMessageContaining("字母和数字");
        assertThatThrownBy(() -> PasswordPolicy.validate("abcdefgh"))
                .isInstanceOf(UserException.class).hasMessageContaining("字母和数字");
    }
}
