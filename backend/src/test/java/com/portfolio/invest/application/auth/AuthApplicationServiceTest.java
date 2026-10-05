package com.portfolio.invest.application.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.invest.domain.user.RememberMeTokenStore;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserErrorCode;
import com.portfolio.invest.domain.user.UserException;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.domain.user.UserRole;
import com.portfolio.invest.domain.user.UserSessionRegistry;
import com.portfolio.invest.domain.user.UserStatus;
import com.portfolio.invest.domain.user.VerificationPurpose;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");
    private static final String EMAIL = "alice@test.local";

    private final UserRepository repo = mock(UserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final EmailCodeService emailCodeService = mock(EmailCodeService.class);
    private final RememberMeTokenStore tokenStore = mock(RememberMeTokenStore.class);
    private final UserSessionRegistry sessionRegistry = mock(UserSessionRegistry.class);
    private AuthApplicationService service;

    @BeforeEach
    void setUp() {
        service = new AuthApplicationService(repo, encoder, emailCodeService, tokenStore, sessionRegistry);
    }

    @DisplayName("注册创建PENDING用户并哈希密码")
    @Test
    void givenValidRegisterCommand_whenRegister_thenCreatePendingUserAndHashPassword() {
        when(encoder.encode("abc12345")).thenReturn("$2a$hash");
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        UserView v = service.register(new RegisterCommand("alice", "abc12345", EMAIL, "123456"));
        assertThat(v.username()).isEqualTo("alice");
        assertThat(v.status()).isEqualTo(UserStatus.PENDING);
        verify(encoder).encode("abc12345");
        verify(emailCodeService).verify(EMAIL, VerificationPurpose.REGISTER, "123456");
        verify(emailCodeService).assertEmailAvailable(EMAIL);
    }

    @DisplayName("用户名为null或空白被拒")
    @Test
    void givenNullOrBlankUsername_whenRegister_thenReject() {
        assertThatThrownBy(() -> service.register(new RegisterCommand(null, "abc12345", EMAIL, "123456")))
                .isInstanceOf(UserException.class).hasMessageContaining("用户名不能为空");
        assertThatThrownBy(() -> service.register(new RegisterCommand("   ", "abc12345", EMAIL, "123456")))
                .isInstanceOf(UserException.class).hasMessageContaining("用户名不能为空");
    }

    @DisplayName("用户名超过64字符被拒")
    @Test
    void givenUsernameOver64Chars_whenRegister_thenReject() {
        assertThatThrownBy(() -> service.register(
                new RegisterCommand("a".repeat(65), "abc12345", EMAIL, "123456")))
                .isInstanceOf(UserException.class).hasMessageContaining("最长64个字符");
    }

    @DisplayName("弱密码被拒")
    @Test
    void givenWeakPassword_whenRegister_thenReject() {
        assertThatThrownBy(() -> service.register(new RegisterCommand("alice", "short1", EMAIL, "123456")))
                .isInstanceOf(UserException.class).hasMessageContaining("至少8位");
    }

    @DisplayName("用户名已存在且非REJECTED被拒")
    @Test
    void givenExistingNonRejectedUsername_whenRegister_thenReject() {
        when(repo.findByUsername("alice")).thenReturn(Optional.of(User.register("alice", "h").approve()));
        assertThatThrownBy(() -> service.register(new RegisterCommand("alice", "abc12345", EMAIL, "123456")))
                .isInstanceOf(UserException.class).hasMessageContaining("用户名已存在");
    }

    @DisplayName("被拒用户同名重新注册复用行")
    @Test
    void givenRejectedUserReregisters_whenRegister_thenReuseRow() {
        User rejected = User.register("alice", "h").reject();
        when(repo.findByUsername("alice")).thenReturn(Optional.of(rejected));
        when(encoder.encode("abc12345")).thenReturn("$2a$hash");
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        UserView v = service.register(new RegisterCommand("alice", "abc12345", EMAIL, "123456"));
        assertThat(v.status()).isEqualTo(UserStatus.PENDING);
        verify(repo).save(argThat(u -> u.passwordHash().equals("$2a$hash")));
    }

    @DisplayName("被拒用户沿用原邮箱重新注册不触发邮箱占用")
    @Test
    void givenRejectedUserWithSameEmail_whenRegister_thenSucceedsWithoutEmailTaken() {
        // R2：assertEmailAvailable 语义是「未被他人绑定」——自己的旧行不算占用
        User rejected = User.register("alice", "h", EMAIL).reject();
        when(repo.findByUsername("alice")).thenReturn(Optional.of(rejected));
        when(encoder.encode("abc12345")).thenReturn("$2a$hash");
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserView v = service.register(new RegisterCommand("alice", "abc12345", EMAIL, "123456"));

        assertThat(v.status()).isEqualTo(UserStatus.PENDING);
        verify(repo).save(argThat(u -> u.passwordHash().equals("$2a$hash")));
        // 沿用原邮箱时跳过占用检查（不查 findByEmail，不会被自己的旧行误判 EMAIL_TAKEN）
        verify(emailCodeService, never()).assertEmailAvailable(any());
    }

    @DisplayName("并发注册触发唯一索引冲突映射为USERNAME_TAKEN")
    @Test
    void givenUniqueConflict_whenRegister_thenMapToUsernameTaken() {
        when(repo.findByUsername("alice")).thenReturn(Optional.empty());
        when(encoder.encode("abc12345")).thenReturn("$2a$hash");
        when(repo.save(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"));
        assertThatThrownBy(() -> service.register(new RegisterCommand("alice", "abc12345", EMAIL, "123456")))
                .isInstanceOf(UserException.class)
                .satisfies(e -> assertThat(((UserException) e).getCode()).isEqualTo(UserErrorCode.USERNAME_TAKEN));
    }

    @DisplayName("并发窗口邮箱唯一索引冲突映射为EMAIL_TAKEN")
    @Test
    void givenEmailUniqueConflict_whenRegister_thenMapToEmailTaken() {
        when(repo.findByUsername("alice")).thenReturn(Optional.empty());
        when(encoder.encode("abc12345")).thenReturn("$2a$hash");
        when(repo.save(any())).thenThrow(
                new org.springframework.dao.DataIntegrityViolationException("uk_app_user_email 约束冲突"));
        assertThatThrownBy(() -> service.register(new RegisterCommand("alice", "abc12345", EMAIL, "123456")))
                .isInstanceOf(UserException.class)
                .satisfies(e -> assertThat(((UserException) e).getCode()).isEqualTo(UserErrorCode.EMAIL_TAKEN));
    }

    @DisplayName("错码注册在创建账号前拦截")
    @Test
    void givenInvalidCode_whenRegister_thenRejectBeforeUserCreation() {
        when(repo.findByUsername("alice")).thenReturn(Optional.empty());
        doThrow(new UserException(UserErrorCode.CODE_INVALID, "验证码错误或已失效"))
                .when(emailCodeService).verify(EMAIL, VerificationPurpose.REGISTER, "000000");

        assertThatThrownBy(() -> service.register(new RegisterCommand("alice", "abc12345", EMAIL, "000000")))
                .isInstanceOf(UserException.class)
                .satisfies(e -> assertThat(((UserException) e).getCode()).isEqualTo(UserErrorCode.CODE_INVALID));
        // 码先消费再落库（FR-A3）：错码不产生任何用户行、不消耗哈希计算
        verify(repo, never()).save(any());
        verify(encoder, never()).encode(anyString());
    }

    @DisplayName("重置密码：验码通过换密码并吊销全部rememberMe令牌")
    @Test
    void givenValidResetRequest_whenResetPassword_thenSaveNewHashAndRevokeTokens() {
        User user = User.reconstitute(1L, "alice", "$2a$old", UserRole.USER, UserStatus.APPROVED, true,
                EMAIL, true, NOW.minusSeconds(3600), NOW.minusSeconds(3600));
        when(emailCodeService.findResettableUser("alice")).thenReturn(Optional.of(user));
        when(encoder.encode("new12345")).thenReturn("$2a$new");
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.resetPassword("alice", "123456", "new12345");

        verify(emailCodeService).verify(EMAIL, VerificationPurpose.RESET, "123456");
        verify(repo).save(argThat(u -> u.passwordHash().equals("$2a$new")));
        verify(tokenStore).removeUserTokens("alice");
        verify(sessionRegistry).expireAll("alice"); // B14：换密码同时吊销全部会话
    }

    @DisplayName("重置密码：错码拒绝且不改库不吊销令牌")
    @Test
    void givenWrongResetCode_whenResetPassword_thenRejectWithoutSaving() {
        User user = User.reconstitute(1L, "alice", "$2a$old", UserRole.USER, UserStatus.APPROVED, true,
                EMAIL, true, NOW.minusSeconds(3600), NOW.minusSeconds(3600));
        when(emailCodeService.findResettableUser("alice")).thenReturn(Optional.of(user));
        doThrow(new UserException(UserErrorCode.CODE_INVALID, "验证码错误或已失效"))
                .when(emailCodeService).verify(EMAIL, VerificationPurpose.RESET, "000000");

        assertThatThrownBy(() -> service.resetPassword("alice", "000000", "new12345"))
                .isInstanceOf(UserException.class)
                .satisfies(e -> assertThat(((UserException) e).getCode()).isEqualTo(UserErrorCode.CODE_INVALID));
        verify(repo, never()).save(any());
        verify(tokenStore, never()).removeUserTokens(any());
        verify(sessionRegistry, never()).expireAll(anyString()); // B14：失败路径不吊销会话
    }

    @DisplayName("重置密码：不可找回标识按错码处理（CODE_INVALID，不枚举账号状态）")
    @Test
    void givenNonResettableIdentifier_whenResetPassword_thenCodeInvalidLikeWrongCode() {
        when(emailCodeService.findResettableUser("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetPassword("ghost", "123456", "new12345"))
                .isInstanceOf(UserException.class)
                .satisfies(e -> {
                    assertThat(((UserException) e).getCode()).isEqualTo(UserErrorCode.CODE_INVALID);
                    assertThat(e.getMessage()).isEqualTo("验证码错误或已失效"); // 与错码文案逐字节一致
                });
        verify(emailCodeService, never()).verify(anyString(), any(), anyString());
        verify(repo, never()).save(any());
        verify(tokenStore, never()).removeUserTokens(any());
    }
}
