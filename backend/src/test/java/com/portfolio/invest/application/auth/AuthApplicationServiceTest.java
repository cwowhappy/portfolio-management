package com.portfolio.invest.application.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserException;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.domain.user.UserStatus;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.portfolio.invest.domain.user.UserErrorCode;

class AuthApplicationServiceTest {

    private final UserRepository repo = mock(UserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private AuthApplicationService service;

    @BeforeEach
    void setUp() {
        service = new AuthApplicationService(repo, encoder);
    }

    @DisplayName("注册创建PENDING用户并哈希密码")
    @Test
    void givenValidRegisterCommand_whenRegister_thenCreatePendingUserAndHashPassword() {
        when(encoder.encode("abc12345")).thenReturn("$2a$hash");
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        UserView v = service.register(new RegisterCommand("alice", "abc12345"));
        assertThat(v.username()).isEqualTo("alice");
        assertThat(v.status()).isEqualTo(UserStatus.PENDING);
        verify(encoder).encode("abc12345");
    }

    @DisplayName("用户名为null或空白被拒")
    @Test
    void givenNullOrBlankUsername_whenRegister_thenReject() {
        assertThatThrownBy(() -> service.register(new RegisterCommand(null, "abc12345")))
                .isInstanceOf(UserException.class).hasMessageContaining("用户名不能为空");
        assertThatThrownBy(() -> service.register(new RegisterCommand("   ", "abc12345")))
                .isInstanceOf(UserException.class).hasMessageContaining("用户名不能为空");
    }

    @DisplayName("用户名超过64字符被拒")
    @Test
    void givenUsernameOver64Chars_whenRegister_thenReject() {
        assertThatThrownBy(() -> service.register(new RegisterCommand("a".repeat(65), "abc12345")))
                .isInstanceOf(UserException.class).hasMessageContaining("最长64个字符");
    }

    @DisplayName("弱密码被拒")
    @Test
    void givenWeakPassword_whenRegister_thenReject() {
        assertThatThrownBy(() -> service.register(new RegisterCommand("alice", "short1")))
                .isInstanceOf(UserException.class).hasMessageContaining("至少8位");
    }

    @DisplayName("用户名已存在且非REJECTED被拒")
    @Test
    void givenExistingNonRejectedUsername_whenRegister_thenReject() {
        when(repo.findByUsername("alice")).thenReturn(Optional.of(User.register("alice", "h").approve()));
        assertThatThrownBy(() -> service.register(new RegisterCommand("alice", "abc12345")))
                .isInstanceOf(UserException.class).hasMessageContaining("用户名已存在");
    }

    @DisplayName("被拒用户同名重新注册复用行")
    @Test
    void givenRejectedUserReregisters_whenRegister_thenReuseRow() {
        User rejected = User.register("alice", "h").reject();
        when(repo.findByUsername("alice")).thenReturn(Optional.of(rejected));
        when(encoder.encode("abc12345")).thenReturn("$2a$hash");
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        UserView v = service.register(new RegisterCommand("alice", "abc12345"));
        assertThat(v.status()).isEqualTo(UserStatus.PENDING);
        verify(repo).save(argThat(u -> u.passwordHash().equals("$2a$hash")));
    }

    @DisplayName("并发注册触发唯一索引冲突映射为USERNAME_TAKEN")
    @Test
    void givenUniqueConflict_whenRegister_thenMapToUsernameTaken() {
        when(repo.findByUsername("alice")).thenReturn(Optional.empty());
        when(encoder.encode("abc12345")).thenReturn("$2a$hash");
        when(repo.save(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"));
        assertThatThrownBy(() -> service.register(new RegisterCommand("alice", "abc12345")))
                .isInstanceOf(UserException.class)
                .satisfies(e -> assertThat(((UserException) e).getCode()).isEqualTo(UserErrorCode.USERNAME_TAKEN));
    }
}
