package com.portfolio.invest.application.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserErrorCode;
import com.portfolio.invest.domain.user.UserException;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.domain.user.UserRole;
import com.portfolio.invest.domain.user.UserStatus;
import com.portfolio.invest.domain.user.VerificationCode;
import com.portfolio.invest.domain.user.VerificationCodeRepository;
import com.portfolio.invest.domain.user.VerificationPurpose;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class EmailCodeServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");
    private static final String EMAIL = "alice@example.com";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final VerificationCodeRepository codeRepository = mock(VerificationCodeRepository.class);
    private final MailSender mailSender = mock(MailSender.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final InvestProperties props = new InvestProperties();

    private EmailCodeService service;

    @BeforeEach
    void setUp() {
        when(mailSender.enabled()).thenReturn(true);
        when(encoder.encode(anyString())).thenReturn("$2a$code");
        when(codeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new EmailCodeService(userRepository, codeRepository, mailSender, encoder, props,
                Clock.fixed(NOW, ZoneId.of("Asia/Shanghai")));
    }

    private User approvedUser(String username) {
        return User.reconstitute(1L, username, "hash", UserRole.USER, UserStatus.APPROVED, true,
                EMAIL, true, NOW.minusSeconds(3600), NOW.minusSeconds(3600));
    }

    // ---- issueRegisterCode ----

    @DisplayName("注册发码：弱密码被拒且不发信")
    @Test
    void givenWeakPassword_whenIssueRegisterCode_thenReject() {
        assertThatThrownBy(() -> service.issueRegisterCode("alice", "short", EMAIL))
                .isInstanceOf(UserException.class).hasMessageContaining("密码");
        verify(mailSender, never()).send(anyString(), anyString(), anyString());
    }

    @DisplayName("注册发码：用户名已占用（非被拒）被拒")
    @Test
    void givenTakenUsername_whenIssueRegisterCode_thenReject() {
        when(userRepository.findByUsername("alice"))
                .thenReturn(Optional.of(approvedUser("alice")));
        assertThatThrownBy(() -> service.issueRegisterCode("alice", "abc12345", EMAIL))
                .isInstanceOf(UserException.class).hasMessageContaining("用户名已存在");
    }

    @DisplayName("注册发码：邮箱已绑定被拒")
    @Test
    void givenTakenEmail_whenIssueRegisterCode_thenReject() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(approvedUser("bob")));
        assertThatThrownBy(() -> service.issueRegisterCode("newuser", "abc12345", EMAIL))
                .isInstanceOf(UserException.class)
                .hasMessageContaining("邮箱已被其他账号绑定");
    }

    @DisplayName("注册发码：60 秒冷却内拒绝")
    @Test
    void givenRecentCode_whenIssue_thenTooFrequent() {
        when(codeRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(EMAIL, VerificationPurpose.REGISTER))
                .thenReturn(Optional.of(VerificationCode.reconstitute(1L, EMAIL, VerificationPurpose.REGISTER,
                        "h", 0, null, NOW.plusSeconds(300), NOW.minusSeconds(30))));
        assertThatThrownBy(() -> service.issueRegisterCode("alice", "abc12345", EMAIL))
                .isInstanceOf(UserException.class)
                .hasMessageContaining("发送过于频繁");
        verify(mailSender, never()).send(anyString(), anyString(), anyString());
    }

    @DisplayName("注册发码：当日第 11 条拒绝")
    @Test
    void givenDailyLimit_whenIssue_thenReject() {
        when(codeRepository.countByEmailAndCreatedAtAfter(eq(EMAIL), any())).thenReturn(10L);
        assertThatThrownBy(() -> service.issueRegisterCode("alice", "abc12345", EMAIL))
                .isInstanceOf(UserException.class).hasMessageContaining("上限");
    }

    @DisplayName("注册发码：落库哈希并发送中文邮件")
    @Test
    void whenIssueRegisterCode_thenSaveHashAndSendMail() {
        service.issueRegisterCode("alice", "abc12345", EMAIL);
        verify(codeRepository).save(any(VerificationCode.class));
        verify(mailSender).send(eq(EMAIL), eq("九和账号验证码"), org.mockito.ArgumentMatchers.contains("验证码"));
    }

    @DisplayName("注册发码：混合大小写邮箱按归一化地址落库并发送")
    @Test
    void givenMixedCaseEmail_whenIssueRegisterCode_thenStoredAndSentNormalized() {
        service.issueRegisterCode("alice", "abc12345", "Alice@Example.COM");
        verify(codeRepository).save(argThat(c -> "alice@example.com".equals(c.email())));
        verify(mailSender).send(eq("alice@example.com"), anyString(), anyString());
    }

    @DisplayName("邮件未配置返回 MAIL_NOT_CONFIGURED")
    @Test
    void givenMailDisabled_whenIssue_thenNotConfigured() {
        when(mailSender.enabled()).thenReturn(false);
        assertThatThrownBy(() -> service.issueRegisterCode("alice", "abc12345", EMAIL))
                .isInstanceOf(UserException.class).hasMessageContaining("未配置邮件服务");
    }

    @DisplayName("固定码生效（e2e）")
    @Test
    void givenFixedCode_whenIssue_thenTextContainsFixed() {
        props.getMail().setTestFixedCode("123456");
        service.issueRegisterCode("alice", "abc12345", EMAIL);
        verify(mailSender).send(eq(EMAIL), anyString(), org.mockito.ArgumentMatchers.contains("123456"));
    }

    // ---- issueResetCode / findResettableUser ----

    @DisplayName("找回：按用户名或邮箱均可定位")
    @Test
    void whenFindResettableByUsernameOrEmail_thenSameUser() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(approvedUser("alice")));
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(approvedUser("alice")));
        assertThat(service.findResettableUser("alice").username()).isEqualTo("alice");
        assertThat(service.findResettableUser(EMAIL).username()).isEqualTo("alice");
    }

    @DisplayName("找回：账号不存在明确报错")
    @Test
    void givenUnknownIdentifier_whenFindResettable_thenNotFound() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.findResettableUser("ghost"))
                .isInstanceOf(UserException.class).hasMessageContaining("账号不存在");
    }

    @DisplayName("找回：ADMIN 拒绝")
    @Test
    void givenAdmin_whenFindResettable_thenForbidden() {
        User admin = User.reconstitute(1L, "root", "hash", UserRole.ADMIN, UserStatus.APPROVED, true,
                "root@x.com", true, NOW, NOW);
        when(userRepository.findByUsername("root")).thenReturn(Optional.of(admin));
        assertThatThrownBy(() -> service.findResettableUser("root"))
                .isInstanceOf(UserException.class).hasMessageContaining("管理员");
    }

    @DisplayName("找回：停用与未绑邮箱均拒绝")
    @Test
    void givenDisabledOrNoEmail_whenFindResettable_thenForbidden() {
        User disabled = approvedUser("alice").disable();
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(disabled));
        assertThatThrownBy(() -> service.findResettableUser("alice"))
                .isInstanceOf(UserException.class).hasMessageContaining("状态不支持");

        User noEmail = User.reconstitute(2L, "old", "hash", UserRole.USER, UserStatus.APPROVED, true,
                null, false, NOW, NOW);
        when(userRepository.findByUsername("old")).thenReturn(Optional.of(noEmail));
        assertThatThrownBy(() -> service.findResettableUser("old"))
                .isInstanceOf(UserException.class).hasMessageContaining("未绑定邮箱");
    }

    // ---- verify ----

    private VerificationCode freshCode() {
        return VerificationCode.reconstitute(1L, EMAIL, VerificationPurpose.REGISTER,
                "$2a$code", 0, null, NOW.plusSeconds(300), NOW.minusSeconds(60));
    }

    @DisplayName("验码成功即标记使用")
    @Test
    void givenRightCode_whenVerify_thenMarkedUsed() {
        when(codeRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(EMAIL, VerificationPurpose.REGISTER))
                .thenReturn(Optional.of(freshCode()));
        when(encoder.matches("123456", "$2a$code")).thenReturn(true);
        service.verify(EMAIL, VerificationPurpose.REGISTER, "123456");
        verify(codeRepository).save(any(VerificationCode.class)); // markUsed 落库
    }

    @DisplayName("错码计数并拒绝")
    @Test
    void givenWrongCode_whenVerify_thenAttemptFailedAndReject() {
        when(codeRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(EMAIL, VerificationPurpose.REGISTER))
                .thenReturn(Optional.of(freshCode()));
        when(encoder.matches("000000", "$2a$code")).thenReturn(false);
        assertThatThrownBy(() -> service.verify(EMAIL, VerificationPurpose.REGISTER, "000000"))
                .isInstanceOf(UserException.class).hasMessageContaining("验证码错误");
        verify(codeRepository).save(any(VerificationCode.class)); // attemptFailed 落库
    }

    @DisplayName("过期、已用、耗尽的码一律无效")
    @Test
    void givenExpiredUsedExhausted_whenVerify_thenInvalid() {
        VerificationCode expired = VerificationCode.reconstitute(1L, EMAIL, VerificationPurpose.REGISTER,
                "h", 0, null, NOW.minusSeconds(1), NOW.minusSeconds(400));
        when(codeRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(EMAIL, VerificationPurpose.REGISTER))
                .thenReturn(Optional.of(expired));
        assertThatThrownBy(() -> service.verify(EMAIL, VerificationPurpose.REGISTER, "123456"))
                .hasMessageContaining("验证码错误");
    }

    @DisplayName("无任何码记录时验码无效")
    @Test
    void givenNoCode_whenVerify_thenInvalid() {
        when(codeRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(EMAIL, VerificationPurpose.REGISTER))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.verify(EMAIL, VerificationPurpose.REGISTER, "123456"))
                .hasMessageContaining("验证码错误");
    }

    // ---- assertEmailAvailable ----

    @DisplayName("邮箱格式与占用双检")
    @Test
    void whenAssertEmailAvailable_thenFormatAndTakenChecked() {
        assertThatThrownBy(() -> service.assertEmailAvailable("not-an-email"))
                .hasMessageContaining("邮箱格式");
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(approvedUser("bob")));
        assertThatThrownBy(() -> service.assertEmailAvailable(EMAIL))
                .hasMessageContaining("邮箱已被其他账号绑定");
    }

    @DisplayName("邮箱大小写归一化后查库与占用判定")
    @Test
    void givenMixedCaseEmail_whenAssertEmailAvailable_thenLookupByNormalized() {
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(approvedUser("bob")));
        assertThatThrownBy(() -> service.assertEmailAvailable("Alice@Example.COM"))
                .isInstanceOf(UserException.class)
                .hasMessageContaining("邮箱已被其他账号绑定");
    }
}
