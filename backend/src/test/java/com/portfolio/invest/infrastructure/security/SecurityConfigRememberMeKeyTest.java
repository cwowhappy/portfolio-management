package com.portfolio.invest.infrastructure.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** B-22：remember-me 签名 key 去兜底——缺失/空白拒绝启动。 */
class SecurityConfigRememberMeKeyTest {

    @Test
    void 空白key拒绝启动() {
        assertThatThrownBy(() -> SecurityConfig.requireRememberMeKey(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REMEMBER_ME_KEY");
        assertThatThrownBy(() -> SecurityConfig.requireRememberMeKey(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REMEMBER_ME_KEY");
        assertThatThrownBy(() -> SecurityConfig.requireRememberMeKey("   "))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 显式配置key通过() {
        assertThat(SecurityConfig.requireRememberMeKey("prod-secret-key")).isEqualTo("prod-secret-key");
    }
}
