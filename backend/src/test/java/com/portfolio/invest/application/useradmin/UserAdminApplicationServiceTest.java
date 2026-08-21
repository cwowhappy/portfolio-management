package com.portfolio.invest.application.useradmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserException;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.domain.user.UserRole;
import com.portfolio.invest.domain.user.UserStatus;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class UserAdminApplicationServiceTest {

    private final UserRepository repo = mock(UserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private UserAdminApplicationService service;

    @BeforeEach
    void setUp() {
        service = new UserAdminApplicationService(repo, encoder);
        when(repo.save(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private User pendingUser(long id) {
        return User.register("u" + id, "h").withId(id);
    }

    @Test
    void 审核通过() {
        when(repo.findById(1L)).thenReturn(Optional.of(pendingUser(1L)));
        UserAdminView v = service.approve(1L);
        assertThat(v.status()).isEqualTo(UserStatus.APPROVED.name());
    }

    @Test
    void 拒绝后状态为REJECTED() {
        when(repo.findById(1L)).thenReturn(Optional.of(pendingUser(1L)));
        assertThat(service.reject(1L).status()).isEqualTo(UserStatus.REJECTED.name());
    }

    @Test
    void 停用与启用() {
        when(repo.findById(1L)).thenReturn(Optional.of(pendingUser(1L).approve()));
        assertThat(service.disable(1L).enabled()).isFalse();
        when(repo.findById(1L)).thenReturn(Optional.of(pendingUser(1L).approve().disable()));
        assertThat(service.enable(1L).enabled()).isTrue();
    }

    @Test
    void 重置密码() {
        when(repo.findById(1L)).thenReturn(Optional.of(pendingUser(1L).approve()));
        when(encoder.encode("xyz12345")).thenReturn("$2a$new");
        assertThat(service.resetPassword(1L, "xyz12345").enabled()).isTrue();
    }

    @Test
    void 不能对管理员操作() {
        when(repo.findById(2L)).thenReturn(Optional.of(
                User.reconstitute(null, "admin", "h", UserRole.ADMIN,
                        UserStatus.APPROVED, true, null, null)));
        assertThatThrownBy(() -> service.disable(2L))
                .isInstanceOf(UserException.class).hasMessageContaining("管理员");
    }

    @Test
    void 用户不存在抛异常() {
        when(repo.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.approve(9L))
                .isInstanceOf(UserException.class).hasMessageContaining("不存在");
    }
}
