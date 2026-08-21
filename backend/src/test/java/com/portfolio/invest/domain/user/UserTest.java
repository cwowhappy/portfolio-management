package com.portfolio.invest.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class UserTest {

    private static User newUser() {
        return User.register("alice", "hash");
    }

    @Test
    void 注册初始为PENDING_USER() {
        User u = newUser();
        assertThat(u.status()).isEqualTo(UserStatus.PENDING);
        assertThat(u.role()).isEqualTo(UserRole.USER);
        assertThat(u.enabled()).isTrue();
        assertThat(u.canLogin()).isFalse();
    }

    @Test
    void 审核通过后可登录() {
        assertThat(newUser().approve().canLogin()).isTrue();
    }

    @Test
    void 被拒后重新注册恢复PENDING() {
        User rejected = newUser().reject();
        assertThat(rejected.status()).isEqualTo(UserStatus.REJECTED);
        User re = rejected.reRegister("newhash");
        assertThat(re.status()).isEqualTo(UserStatus.PENDING);
        assertThat(re.passwordHash()).isEqualTo("newhash");
    }

    @Test
    void 停用后不可登录且再启用可恢复() {
        assertThat(newUser().approve().disable().canLogin()).isFalse();
        assertThat(newUser().approve().disable().enable().canLogin()).isTrue();
    }

    @Test
    void 对非PENDING用户审核抛异常() {
        assertThatThrownBy(() -> newUser().approve().approve())
                .isInstanceOf(UserException.class).hasMessageContaining("状态");
    }
}
