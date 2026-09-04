package com.portfolio.invest.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UserTest {

    private static User newUser() {
        return User.register("alice", "hash");
    }

    @DisplayName("注册初始为PENDING_USER")
    @Test
    void whenRegister_thenStartAsPendingUser() {
        User u = newUser();
        assertThat(u.status()).isEqualTo(UserStatus.PENDING);
        assertThat(u.role()).isEqualTo(UserRole.USER);
        assertThat(u.enabled()).isTrue();
        assertThat(u.canLogin()).isFalse();
    }

    @DisplayName("审核通过后可登录")
    @Test
    void givenApprovedUser_whenCanLogin_thenTrue() {
        assertThat(newUser().approve().canLogin()).isTrue();
    }

    @DisplayName("被拒后重新注册恢复PENDING")
    @Test
    void givenRejectedUser_whenReRegister_thenResetToPending() {
        User rejected = newUser().reject();
        assertThat(rejected.status()).isEqualTo(UserStatus.REJECTED);
        User re = rejected.reRegister("newhash");
        assertThat(re.status()).isEqualTo(UserStatus.PENDING);
        assertThat(re.passwordHash()).isEqualTo("newhash");
    }

    @DisplayName("停用后不可登录且再启用可恢复")
    @Test
    void givenApprovedUser_whenDisableThenEnable_thenLoginAbilityRecovers() {
        assertThat(newUser().approve().disable().canLogin()).isFalse();
        assertThat(newUser().approve().disable().enable().canLogin()).isTrue();
    }

    @DisplayName("对非PENDING用户审核抛异常")
    @Test
    void givenNonPendingUser_whenApprove_thenThrow() {
        assertThatThrownBy(() -> newUser().approve().approve())
                .isInstanceOf(UserException.class).hasMessageContaining("状态");
    }
}
