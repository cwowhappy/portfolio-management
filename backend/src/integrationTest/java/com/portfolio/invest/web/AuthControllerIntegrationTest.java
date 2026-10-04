package com.portfolio.invest.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.domain.user.UserRole;
import com.portfolio.invest.domain.user.UserStatus;
import com.portfolio.invest.domain.user.VerificationCode;
import com.portfolio.invest.domain.user.VerificationCodeRepository;
import com.portfolio.invest.domain.user.VerificationPurpose;
import com.portfolio.invest.infrastructure.security.SecurityConfig;
import com.portfolio.invest.support.PostgresTestSupport;
import com.portfolio.invest.support.RecordingMailSender;
import com.portfolio.invest.support.TestCodes;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerIntegrationTest extends PostgresTestSupport {

    /** 发信桩：@Primary 覆盖未配置 SMTP 的 SmtpMailSender，发码走 RecordingMailSender 供断言/取码。 */
    @TestConfiguration
    static class MailStub {
        @Bean
        @Primary
        RecordingMailSender recordingMailSender() {
            return new RecordingMailSender();
        }

        /**
         * 过渡期测试脚手架：UserToolkitFactory 的 Duration toolTimeout 依赖（BE-B 接线中）在
         * 主装配供给前由测试侧兜底；@ConditionalOnMissingBean 保证主装配一旦提供即自动退出。
         */
        @Bean
        @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(java.time.Duration.class)
        java.time.Duration mcpToolTimeoutFallback() {
            return java.time.Duration.ofSeconds(30);
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired VerificationCodeRepository codeRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired RecordingMailSender mailStub;

    @BeforeEach
    void clearMailStub() {
        // 单例桩跨用例累积，清空让各用例只看自己发出的邮件（断言不依赖用例执行顺序）
        mailStub.sent.clear();
    }

    @DisplayName("注册流程：发码-注册-待审核")
    @Test
    void givenIssuedCode_whenRegister_thenPendingWithVerifiedEmail() throws Exception {
        mockMvc.perform(post("/api/auth/register-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"mail_alice\",\"password\":\"abc12345\",\"email\":\"mail_alice@test.local\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("验证码已发送"));
        assertThat(mailStub.sent).hasSize(1);
        assertThat(mailStub.sent.get(0).to()).isEqualTo("mail_alice@test.local");

        String code = TestCodes.extractSixDigits(mailStub.sent.get(0).text());
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"mail_alice\",\"password\":\"abc12345\","
                                + "\"email\":\"mail_alice@test.local\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));

        // 邮箱随注册落库且标记已验证（注册流程邮箱来自持码验证的用户输入）
        var saved = userRepository.findByEmail("mail_alice@test.local").orElseThrow();
        assertThat(saved.emailVerified()).isTrue();
    }

    @DisplayName("错码注册返回400 CODE_INVALID")
    @Test
    void givenIssuedCode_whenRegisterWithWrongCode_thenCodeInvalid() throws Exception {
        mockMvc.perform(post("/api/auth/register-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"mail_badcode\",\"password\":\"abc12345\",\"email\":\"mail_badcode@test.local\"}"))
                .andExpect(status().isOk());
        String issued = TestCodes.extractSixDigits(mailStub.sent.get(0).text());
        String wrong = issued.equals("000000") ? "111111" : "000000";

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"mail_badcode\",\"password\":\"abc12345\","
                                + "\"email\":\"mail_badcode@test.local\",\"code\":\"" + wrong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CODE_INVALID"));
        assertThat(userRepository.findByUsername("mail_badcode")).isEmpty();
    }

    @DisplayName("重复邮箱注册返回400 EMAIL_TAKEN")
    @Test
    void givenEmailAlreadyRegistered_whenIssueCodeForSameEmail_thenEmailTaken() throws Exception {
        register("mail_dup_a", "abc12345");

        // 第二个账号对同一邮箱发码：预检即拦截（该邮箱已随首个账号落库）
        mockMvc.perform(post("/api/auth/register-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"mail_dup_b\",\"password\":\"abc12345\",\"email\":\"mail_dup_a@test.local\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
    }

    @DisplayName("注册后待审核不能登录")
    @Test
    void givenRegisteredPendingUser_whenLogin_thenForbidden() throws Exception {
        register("auth_alice", "abc12345");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"auth_alice\",\"password\":\"abc12345\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_PENDING"));
    }

    @DisplayName("审核通过后登录成功并访问me")
    @Test
    void givenApprovedUser_whenLogin_thenSucceedsAndMeAccessible() throws Exception {
        register("auth_bob", "abc12345");
        approve("auth_bob");

        var result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"auth_bob\",\"password\":\"abc12345\"}"))
                .andExpect(status().isOk())
                .andReturn();

        // MockMvc 不会依据 JSESSIONID cookie 重建会话，需显式传递登录请求产生的 MockHttpSession
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("auth_bob"));
    }

    @DisplayName("连续5次密码错误→第6次即使密码正确也429+Retry-After，其他用户不受影响")
    @Test
    void givenRepeatedLoginFailures_whenLocked_then429EvenWithCorrectPasswordAndOtherUserUnaffected() throws Exception {
        register("rate_alice", "abc12345");
        approve("rate_alice");
        register("rate_bob", "abc12345");
        approve("rate_bob");

        // 同一 username 连续 5 次密码错误 → 401（行为不变）
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"rate_alice\",\"password\":\"wrongpass\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
        }

        // 第 6 次携带正确密码 → 429 + Retry-After（锁定期内密码正确也拒绝）
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"rate_alice\",\"password\":\"abc12345\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", org.hamcrest.Matchers.notNullValue()))
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"));

        // 不同 username 互不影响：bob 正常登录
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"rate_bob\",\"password\":\"abc12345\"}"))
                .andExpect(status().isOk());
    }

    @DisplayName("错误密码返回401")
    @Test
    void givenRegisteredUser_whenLoginWithWrongPassword_thenUnauthorized() throws Exception {
        register("auth_carol", "abc12345");
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"auth_carol\",\"password\":\"wrongpass\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
    }

    @DisplayName("登录成功后轮换sessionId防会话固定")
    @Test
    void givenAnonymousPreLoginSession_whenLoginSucceeds_thenSessionIdRotated() throws Exception {
        register("auth_dave", "abc12345");
        approve("auth_dave");

        // 登录前已持有匿名会话
        MockHttpSession preLogin = new MockHttpSession();
        String oldId = preLogin.getId();

        var result = mockMvc.perform(post("/api/auth/login").session(preLogin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"auth_dave\",\"password\":\"abc12345\"}"))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession afterLogin = (MockHttpSession) result.getRequest().getSession(false);
        org.assertj.core.api.Assertions.assertThat(afterLogin.getId()).isNotEqualTo(oldId);

        // 轮换后的会话带认证态
        mockMvc.perform(get("/api/auth/me").session(afterLogin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("auth_dave"));
    }

    @DisplayName("rememberMe仅传JSON字段也下发cookie")
    @Test
    void givenApprovedUser_whenLoginWithJsonRememberMe_thenRememberMeCookieIssued() throws Exception {
        register("auth_erin", "abc12345");
        approve("auth_erin");

        // 前端登录只发 JSON body 的 rememberMe 字段，不带 remember-me 请求参数（真实浏览器链路）；
        // 回归：loginSuccess 内部曾因缺该参数静默不下发 cookie
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"auth_erin\",\"password\":\"abc12345\",\"rememberMe\":true}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie()
                        .exists(com.portfolio.invest.infrastructure.security.SecurityConfig.REMEMBER_ME_COOKIE));
    }

    @DisplayName("注册登录结构性校验失败返回400")
    @Test
    void givenBlankUsernameOrMissingPassword_whenRegisterOrLogin_thenBadRequest() throws Exception {
        // 空白用户名注册 → Bean Validation 拦截（H8）；email/code 补齐让用户名成为唯一违例
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"  \",\"password\":\"abc12345\","
                                + "\"email\":\"blank@test.local\",\"code\":\"123456\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("用户名不能为空"));

        // 缺少密码登录 → Bean Validation 拦截，不再落到认证流程
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"auth_bob\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("密码不能为空"));
    }

    @DisplayName("找回全流程：发码-重置-新密码登录-旧码复用作废")
    @Test
    void givenApprovedUser_whenResetPassword_thenLoginWithNewAndCodeBurned() throws Exception {
        // 准备：三段式注册一个用户并 repo 直改 APPROVED（可找回前置）
        register("reset_flow", "abc12345");
        approve("reset_flow");

        // 1) 找回发码 200 + 中性文案 + 邮件发往已验证邮箱
        mockMvc.perform(post("/api/auth/reset-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"reset_flow\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value(com.portfolio.invest.application.auth.EmailCodeService.RESET_NEUTRAL_MESSAGE));
        var resetMail = mailStub.sent.get(mailStub.sent.size() - 1);
        assertThat(resetMail.to()).isEqualTo("reset_flow@test.local");
        String firstCode = TestCodes.extractSixDigits(resetMail.text());

        // 2) 携码重置 200
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"reset_flow\",\"code\":\"" + firstCode
                                + "\",\"newPassword\":\"newpass99\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("密码已重置"));

        // 3) 新密码登录 200
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"reset_flow\",\"password\":\"newpass99\"}"))
                .andExpect(status().isOk());

        // 4) 立即重放已消费的 firstCode（仍是最新码行，无冷却/重发干扰）→ 400 CODE_INVALID：
        //    失败唯一归因 isUsed 拦截——删掉 verify 的 isUsed 分支此断言即红（用后即焚安全回归哨兵）
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"reset_flow\",\"code\":\"" + firstCode
                                + "\",\"newPassword\":\"newpass99\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CODE_INVALID"));

        // 5) 再发一次码（回拨旧码创建时间绕过 60s 发码冷却）后复用旧码 → 400 CODE_INVALID
        //    （旧码已非最新行，哈希必失配——兼证「重发顶掉旧码」语义）
        ageLatestResetCode("reset_flow@test.local");
        mockMvc.perform(post("/api/auth/reset-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"reset_flow@test.local\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"reset_flow\",\"code\":\"" + firstCode
                                + "\",\"newPassword\":\"newpass88\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CODE_INVALID"));
    }

    @DisplayName("reset-code 四分支（不存在/管理员/未绑邮箱/停用）响应一致且均不发信")
    @Test
    void givenNonResettableIdentifiers_whenResetCode_thenIdenticalNeutralResponseAndNoMail() throws Exception {
        // 管理员（绑邮箱）与未绑邮箱普通用户直落库
        seedAdmin("reset_admin2", "admin12345", "reset_admin2@test.local");
        userRepository.save(User.reconstitute(null, "reset_noemail", passwordEncoder.encode("abc12345"),
                UserRole.USER, UserStatus.APPROVED, true, null, false, Instant.now(), Instant.now()));
        // 停用用户：注册审核后停用（注册流程已发信，清空后只看 reset-code 是否发信）
        register("reset_disabled", "abc12345");
        approve("reset_disabled");
        var disabled = userRepository.findByUsername("reset_disabled").orElseThrow();
        userRepository.save(disabled.disable());
        mailStub.sent.clear();

        String neutral = com.portfolio.invest.application.auth.EmailCodeService.RESET_NEUTRAL_MESSAGE;
        mockMvc.perform(post("/api/auth/reset-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"no_such_reset_user\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(neutral));
        mockMvc.perform(post("/api/auth/reset-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"reset_admin2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(neutral));
        mockMvc.perform(post("/api/auth/reset-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"reset_noemail\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(neutral));
        mockMvc.perform(post("/api/auth/reset-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"reset_disabled\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(neutral));
        assertThat(mailStub.sent).isEmpty(); // 四分支均不发信
    }

    @DisplayName("reset-password 不可找回标识按错码处理（400 CODE_INVALID，不枚举）")
    @Test
    void givenUnknownIdentifier_whenResetPassword_thenCodeInvalid() throws Exception {
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"no_such_user\",\"code\":\"123456\",\"newPassword\":\"newpass99\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CODE_INVALID"));
    }

    @DisplayName("重置后 remember-me 吊销")
    @Test
    void givenRememberMeCookie_whenResetPassword_thenCookieInvalid() throws Exception {
        register("reset_remember", "abc12345");
        approve("reset_remember");

        var loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"reset_remember\",\"password\":\"abc12345\",\"rememberMe\":true}"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie rememberMe = loginResult.getResponse().getCookie(SecurityConfig.REMEMBER_ME_COOKIE);
        assertThat(rememberMe).isNotNull();

        // 前置：仅凭 remember-me cookie（不带会话）可访问 me
        var preCheck = mockMvc.perform(get("/api/auth/me").cookie(rememberMe))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("reset_remember"))
                .andReturn();
        // 持久化 remember-me 每次自动登录轮换 token 并下发新 cookie，取最新值继续
        Cookie rotated = preCheck.getResponse().getCookie(SecurityConfig.REMEMBER_ME_COOKIE);
        if (rotated != null) {
            rememberMe = rotated;
        }

        // 自助重置密码（吊销全部 remember-me 令牌）
        mockMvc.perform(post("/api/auth/reset-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"reset_remember\"}"))
                .andExpect(status().isOk());
        String code = TestCodes.extractSixDigits(mailStub.sent.get(mailStub.sent.size() - 1).text());
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"reset_remember\",\"code\":\"" + code
                                + "\",\"newPassword\":\"newpass77\"}"))
                .andExpect(status().isOk());

        // 旧 cookie 对应令牌已删：仅凭 cookie 访问 me → 401
        mockMvc.perform(get("/api/auth/me").cookie(rememberMe))
                .andExpect(status().isUnauthorized());
    }

    /** 三段式注册：发码（邮件桩取码）→ 携码注册；邮箱由用户名派生保证类内唯一。 */
    private void register(String username, String password) throws Exception {
        String email = username + "@test.local";
        mockMvc.perform(post("/api/auth/register-code").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password
                                + "\",\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk());
        String code = TestCodes.extractSixDigits(mailStub.sent.get(mailStub.sent.size() - 1).text());
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password
                                + "\",\"email\":\"" + email + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isCreated());
    }

    private void approve(String username) {
        var user = userRepository.findByUsername(username).orElseThrow();
        userRepository.save(user.approve());
    }

    /** 直存 APPROVED 管理员（参照 UserAdminControllerIntegrationTest.seedAdmin，附带已验证邮箱）。 */
    private void seedAdmin(String username, String password, String email) {
        userRepository.save(User.reconstitute(null, username, passwordEncoder.encode(password),
                UserRole.ADMIN, UserStatus.APPROVED, true, email, true, Instant.now(), Instant.now()));
    }

    /** 回拨该邮箱最新 RESET 码的创建时间 70s：用后即焚用例需在同一测试内二次发码，绕过 60s 冷却。 */
    private void ageLatestResetCode(String email) {
        VerificationCode latest = codeRepository
                .findTopByEmailAndPurposeOrderByCreatedAtDesc(email, VerificationPurpose.RESET)
                .orElseThrow(() -> new AssertionError("缺少 RESET 码行: " + email));
        codeRepository.save(VerificationCode.reconstitute(latest.id(), latest.email(), latest.purpose(),
                latest.codeHash(), latest.attempts(), latest.usedAt(), latest.expiresAt(),
                latest.createdAt().minusSeconds(70)));
    }
}
