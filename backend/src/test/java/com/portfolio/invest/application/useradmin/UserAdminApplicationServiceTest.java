package com.portfolio.invest.application.useradmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.invest.application.auth.EmailCodeService;
import com.portfolio.invest.application.auth.MailSender;
import com.portfolio.invest.domain.user.RememberMeTokenStore;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserErrorCode;
import com.portfolio.invest.domain.user.UserException;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.domain.user.UserRole;
import com.portfolio.invest.domain.user.UserStatus;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

class UserAdminApplicationServiceTest {

    private final UserRepository repo = mock(UserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final RememberMeTokenStore tokenStore = mock(RememberMeTokenStore.class);
    private final EmailCodeService emailCodeService = mock(EmailCodeService.class);
    private final MailSender mailSender = mock(MailSender.class);
    private final org.springframework.context.ApplicationEventPublisher publisher =
            mock(org.springframework.context.ApplicationEventPublisher.class);
    private UserAdminApplicationService service;

    @BeforeEach
    void setUp() {
        service = new UserAdminApplicationService(repo, encoder, tokenStore, emailCodeService, mailSender, publisher);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private User pendingUser(long id) {
        return User.register("u" + id, "h").withId(id);
    }

    private User userWith(long id, String email, boolean emailVerified) {
        return User.reconstitute(id, "u" + id, "h", UserRole.USER, UserStatus.APPROVED, true,
                email, emailVerified, null, null);
    }

    @DisplayName("审核通过")
    @Test
    void givenPendingUser_whenApprove_thenApproved() {
        when(repo.findById(1L)).thenReturn(Optional.of(pendingUser(1L)));
        UserAdminView v = service.approve(1L);
        assertThat(v.status()).isEqualTo(UserStatus.APPROVED.name());
    }

    @DisplayName("拒绝后状态为REJECTED")
    @Test
    void givenPendingUser_whenReject_thenStatusRejected() {
        when(repo.findById(1L)).thenReturn(Optional.of(pendingUser(1L)));
        assertThat(service.reject(1L).status()).isEqualTo(UserStatus.REJECTED.name());
    }

    @DisplayName("停用与启用")
    @Test
    void givenUser_whenDisableAndEnable_thenToggleEnabled() {
        when(repo.findById(1L)).thenReturn(Optional.of(pendingUser(1L).approve()));
        assertThat(service.disable(1L).enabled()).isFalse();
        when(repo.findById(1L)).thenReturn(Optional.of(pendingUser(1L).approve().disable()));
        assertThat(service.enable(1L).enabled()).isTrue();
    }

    @DisplayName("重置密码")
    @Test
    void givenApprovedUser_whenResetPassword_thenSucceed() {
        when(repo.findById(1L)).thenReturn(Optional.of(pendingUser(1L).approve()));
        when(encoder.encode("xyz12345")).thenReturn("$2a$new");
        assertThat(service.resetPassword(1L, "xyz12345").enabled()).isTrue();
    }

    @DisplayName("重置密码后吊销该用户rememberMe令牌")
    @Test
    void givenApprovedUser_whenResetPassword_thenRevokeRememberMeTokens() {
        when(repo.findById(1L)).thenReturn(Optional.of(pendingUser(1L).approve()));
        when(encoder.encode("xyz12345")).thenReturn("$2a$new");
        service.resetPassword(1L, "xyz12345");
        org.mockito.Mockito.verify(tokenStore).removeUserTokens("u1");
    }

    @DisplayName("不能对管理员操作")
    @Test
    void givenAdminUser_whenDisable_thenReject() {
        when(repo.findById(2L)).thenReturn(Optional.of(
                User.reconstitute(null, "admin", "h", UserRole.ADMIN,
                        UserStatus.APPROVED, true, null, null)));
        assertThatThrownBy(() -> service.disable(2L))
                .isInstanceOf(UserException.class).hasMessageContaining("管理员");
    }

    @DisplayName("用户不存在抛异常")
    @Test
    void givenUserNotFound_whenApprove_thenThrowException() {
        when(repo.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.approve(9L))
                .isInstanceOf(UserException.class).hasMessageContaining("不存在");
    }

    @DisplayName("代填邮箱：归一化落库且已验证并尽力发告知邮件")
    @Test
    void givenUserWithoutEmail_whenSetEmail_thenBoundVerifiedAndNotified() {
        when(repo.findById(1L)).thenReturn(Optional.of(userWith(1L, null, false)));

        UserAdminView v = service.setEmail(1L, " New@Target.Local ");

        assertThat(v.email()).isEqualTo("new@target.local");
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().email()).isEqualTo("new@target.local");
        assertThat(saved.getValue().emailVerified()).isTrue();
        verify(mailSender).send(eq("new@target.local"), eq("九和投资邮箱绑定通知"), contains("九和投资账号 u1"));
    }

    @DisplayName("代填邮箱被占抛EMAIL_TAKEN且不落库不发信")
    @Test
    void givenEmailTaken_whenSetEmail_thenEmailTaken() {
        when(repo.findById(1L)).thenReturn(Optional.of(userWith(1L, null, false)));
        doThrow(new UserException(UserErrorCode.EMAIL_TAKEN, "邮箱已被其他账号绑定"))
                .when(emailCodeService).assertEmailAvailable("taken@target.local");

        assertThatThrownBy(() -> service.setEmail(1L, " Taken@Target.Local "))
                .isInstanceOf(UserException.class).hasMessageContaining("邮箱已被其他账号绑定");

        verify(repo, never()).save(any());
        verifyNoInteractions(mailSender);
    }

    @DisplayName("对管理员代填邮箱被拒绝（保护先于幂等比较）")
    @Test
    void givenAdminUser_whenSetEmail_thenForbidden() {
        when(repo.findById(2L)).thenReturn(Optional.of(User.reconstitute(2L, "admin", "h", UserRole.ADMIN,
                UserStatus.APPROVED, true, "admin@x.local", true, null, null)));

        assertThatThrownBy(() -> service.setEmail(2L, "admin@x.local"))
                .isInstanceOf(UserException.class).hasMessageContaining("管理员");

        verifyNoInteractions(emailCodeService, mailSender);
        verify(repo, never()).save(any());
    }

    @DisplayName("现邮箱归一化相等时幂等返回：不校验占用不发信不重绑")
    @Test
    void givenSameEmail_whenSetEmail_thenIdempotentNoMailNoRebind() {
        when(repo.findById(1L)).thenReturn(Optional.of(userWith(1L, "same@test.local", true)));

        UserAdminView v = service.setEmail(1L, " Same@Test.Local ");

        assertThat(v.email()).isEqualTo("same@test.local");
        verifyNoInteractions(emailCodeService);
        verifyNoInteractions(mailSender);
        verify(repo, never()).save(any());
    }

    @DisplayName("告知邮件发送失败不影响绑定结果（NFR-2 尽力而为）")
    @Test
    void givenMailSendFails_whenSetEmail_thenStillBound() {
        when(repo.findById(1L)).thenReturn(Optional.of(userWith(1L, null, false)));
        doThrow(new RuntimeException("smtp down")).when(mailSender)
                .send(eq("mf@target.local"), anyString(), anyString());

        UserAdminView v = service.setEmail(1L, "mf@target.local");

        assertThat(v.email()).isEqualTo("mf@target.local");
        verify(repo).save(any(User.class));
    }

    @DisplayName("审核通过与拒绝发布UserStatusChangedEvent")
    @Test
    void givenPendingUser_whenApproveOrReject_thenPublishesStatusChangedEvent() {
        when(repo.findById(1L)).thenReturn(Optional.of(pendingUser(1L)));
        service.approve(1L);
        verify(publisher).publishEvent(new UserStatusChangedEvent("u1"));

        when(repo.findById(1L)).thenReturn(Optional.of(pendingUser(1L)));
        service.reject(1L);
        verify(publisher, times(2)).publishEvent(new UserStatusChangedEvent("u1"));
    }

    @DisplayName("停用与启用发布UserStatusChangedEvent")
    @Test
    void givenApprovedUser_whenDisableOrEnable_thenPublishesStatusChangedEvent() {
        when(repo.findById(1L)).thenReturn(Optional.of(pendingUser(1L).approve()));
        service.disable(1L);
        verify(publisher).publishEvent(new UserStatusChangedEvent("u1"));

        when(repo.findById(1L)).thenReturn(Optional.of(pendingUser(1L).approve().disable()));
        service.enable(1L);
        verify(publisher, times(2)).publishEvent(new UserStatusChangedEvent("u1"));
    }

    @DisplayName("重置密码不变更状态不发事件")
    @Test
    void givenApprovedUser_whenResetPassword_thenNoStatusChangedEvent() {
        when(repo.findById(1L)).thenReturn(Optional.of(pendingUser(1L).approve()));
        when(encoder.encode("xyz12345")).thenReturn("$2a$new");

        service.resetPassword(1L, "xyz12345");

        verify(repo).save(any(User.class));
        verifyNoInteractions(publisher);
    }

    @DisplayName("代填邮箱不变更状态不发事件")
    @Test
    void givenUserWithoutEmail_whenSetEmail_thenNoStatusChangedEvent() {
        when(repo.findById(1L)).thenReturn(Optional.of(userWith(1L, null, false)));

        service.setEmail(1L, "new@target.local");

        verify(repo).save(any(User.class));
        verifyNoInteractions(publisher);
    }
}
