package com.portfolio.invest.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.application.auth.AuthApplicationService;
import com.portfolio.invest.application.auth.EmailCodeService;
import com.portfolio.invest.application.auth.RegisterCommand;
import com.portfolio.invest.application.auth.UserView;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.domain.user.UserRole;
import com.portfolio.invest.domain.user.UserSessionRegistry;
import com.portfolio.invest.domain.user.UserStatus;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import com.portfolio.invest.infrastructure.security.ActiveUserStatusCache;
import com.portfolio.invest.infrastructure.security.LoginRateLimiter;
import com.portfolio.invest.infrastructure.security.SecurityConfig;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 认证 REST 切片：登录（含 remember-me 分支）/ 注册 / me 的绑定、出参与状态分流。
 * 引入真实 SecurityConfig（/api/auth/login、/register 匿名放行）；凭据认证由
 * AuthenticationManager 打桩，RememberMeServices 打桩以观察 loginSuccess 调用。
 */
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, ActiveUserStatusCache.class})
class AuthControllerSliceTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuthApplicationService authService;
    @MockitoBean
    private EmailCodeService emailCodeService;
    @MockitoBean
    private AuthenticationManager authenticationManager;
    @MockitoBean
    private RememberMeServices rememberMeServices;
    @MockitoBean
    private LoginRateLimiter loginRateLimiter;
    // B14：登录成功后会话登记端口（真实实现见 SpringSessionUserRegistryTest/集成测试）
    @MockitoBean
    private UserSessionRegistry userSessionRegistry;

    // SecurityConfig 装配所需依赖（切片内无真实实现）
    @MockitoBean
    private UserRepository userRepository;
    @MockitoBean
    private UserDetailsService userDetailsService;
    @MockitoBean
    private PersistentTokenRepository persistentTokenRepository;

    private static final Instant NOW = Instant.parse("2026-08-01T02:03:04Z");

    private User user(UserStatus status, boolean enabled) {
        return User.reconstitute(1L, "u", "p", UserRole.USER, status, enabled, NOW, NOW);
    }

    private Authentication authOf(User user) {
        var principal = new AuthenticatedUser(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private void givenAuthenticationSucceeds(User user) {
        when(authenticationManager.authenticate(any())).thenReturn(authOf(user));
    }

    @DisplayName("登录成功且rememberMe为true下发rememberMeCookie")
    @Test
    void givenRememberMeTrue_whenLogin_thenIssueRememberMeCookie() throws Exception {
        givenAuthenticationSucceeds(user(UserStatus.APPROVED, true));
        // RememberMeServices 打桩：loginSuccess 时模拟真实实现写入 remember-me cookie
        doAnswer(inv -> {
            ((HttpServletResponse) inv.getArgument(1))
                    .addCookie(new Cookie(SecurityConfig.REMEMBER_ME_COOKIE, "token-value"));
            return null;
        }).when(rememberMeServices).loginSuccess(any(), any(), any());

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"u\",\"password\":\"p\",\"rememberMe\":true}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", containsString(SecurityConfig.REMEMBER_ME_COOKIE)))
                .andExpect(jsonPath("$.username").value("u"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.enabled").value(true));

        verify(rememberMeServices).loginSuccess(any(), any(), any());
    }

    @DisplayName("登录成功且rememberMe缺省不下发rememberMeCookie")
    @Test
    void givenRememberMeOmitted_whenLogin_thenNotIssueRememberMeCookie() throws Exception {
        givenAuthenticationSucceeds(user(UserStatus.APPROVED, true));

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"u\",\"password\":\"p\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("u"));

        verify(rememberMeServices, never()).loginSuccess(any(), any(), any());
    }

    @DisplayName("待审核账号登录返回403")
    @Test
    void givenPendingAccount_whenLogin_thenReturn403() throws Exception {
        givenAuthenticationSucceeds(user(UserStatus.PENDING, true));

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"u\",\"password\":\"p\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_PENDING"));
    }

    @DisplayName("已拒绝账号登录返回403")
    @Test
    void givenRejectedAccount_whenLogin_thenReturn403() throws Exception {
        givenAuthenticationSucceeds(user(UserStatus.REJECTED, true));

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"u\",\"password\":\"p\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_REJECTED"));
    }

    @DisplayName("已停用账号登录返回403")
    @Test
    void givenDisabledAccount_whenLogin_thenReturn403() throws Exception {
        givenAuthenticationSucceeds(user(UserStatus.APPROVED, false));

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"u\",\"password\":\"p\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
    }

    @DisplayName("连续5次密码错误后第6次登录429+Retry-After，且不再触发认证")
    @Test
    void givenFiveFailures_whenLoginAgain_then429WithRetryAfter() throws Exception {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("bad credentials"));
        // 前 5 次请求放行（各自计一次失败），第 6 次起限流器判定锁定
        when(loginRateLimiter.blockedForSeconds("u"))
                .thenReturn(OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(),
                        OptionalLong.empty(), OptionalLong.empty(), OptionalLong.of(300L));

        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"u\",\"password\":\"wrong\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
        }
        org.mockito.Mockito.verify(loginRateLimiter, org.mockito.Mockito.times(5)).onFailure("u");

        // 第 6 次即使密码正确（认证可成功）也被 429 拦截，authenticate 不得再被调用
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"u\",\"password\":\"p\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "300"))
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"))
                .andExpect(jsonPath("$.message").value("尝试次数过多，请 5 分钟后再试"));
        org.mockito.Mockito.verify(authenticationManager, org.mockito.Mockito.times(5)).authenticate(any());
        org.mockito.Mockito.verify(loginRateLimiter, org.mockito.Mockito.never()).onSuccess(org.mockito.ArgumentMatchers.anyString());
    }

    @DisplayName("锁定的username不影响其他用户登录")
    @Test
    void givenLockedUsername_whenOtherUsernameLogin_thenSucceeds() throws Exception {
        when(loginRateLimiter.blockedForSeconds("u")).thenReturn(OptionalLong.of(300L));
        when(loginRateLimiter.blockedForSeconds("v")).thenReturn(OptionalLong.empty());
        givenAuthenticationSucceeds(user(UserStatus.APPROVED, true));

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"v\",\"password\":\"p\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("u"));
        org.mockito.Mockito.verify(loginRateLimiter).onSuccess("v");
    }

    @DisplayName("登录成功清零限流计数")
    @Test
    void givenSuccessfulLogin_whenLogin_thenResetCounter() throws Exception {
        when(loginRateLimiter.blockedForSeconds("u")).thenReturn(OptionalLong.empty());
        givenAuthenticationSucceeds(user(UserStatus.APPROVED, true));

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"u\",\"password\":\"p\"}"))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(loginRateLimiter).onSuccess("u");
        org.mockito.Mockito.verify(loginRateLimiter, org.mockito.Mockito.never()).onFailure(org.mockito.ArgumentMatchers.anyString());
    }

    @DisplayName("密码错误返回401")
    @Test
    void givenWrongPassword_whenLogin_thenReturn401() throws Exception {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("bad credentials"));

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"u\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
    }

    @DisplayName("登录用户名为空校验失败返回400")
    @Test
    void givenEmptyUsername_whenLogin_thenReturn400() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"p\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("用户名不能为空"));
    }

    @DisplayName("me已认证返回当前用户")
    @Test
    void givenAuthenticatedUser_whenGetMe_thenReturnCurrentUser() throws Exception {
        when(userRepository.findByUsername("u")).thenReturn(Optional.of(user(UserStatus.APPROVED, true)));

        mvc.perform(get("/api/auth/me").with(authentication(authOf(user(UserStatus.APPROVED, true)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("u"))
                .andExpect(jsonPath("$.createdAt").value("2026-08-01T02:03:04Z"));
    }

    @DisplayName("me未认证返回401")
    @Test
    void givenNoAuthentication_whenGetMe_thenReturn401() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @DisplayName("me认证主体非AuthenticatedUser时返回401")
    @Test
    void givenPrincipalNotAuthenticatedUser_whenGetMe_thenReturn401() throws Exception {
        // 认证已建立但主体不是本系统的 AuthenticatedUser（如匿名/其他机制）→ 未登录
        mvc.perform(get("/api/auth/me").with(authentication(
                        new UsernamePasswordAuthenticationToken("anonymous", null, java.util.List.of()))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @DisplayName("找回密码发码返回中性文案（B5 防枚举，具体分支由服务层保证）")
    @Test
    void givenResetCodeRequest_whenResetCode_thenNeutralMessage() throws Exception {
        when(emailCodeService.issueResetCode("ghost"))
                .thenReturn(com.portfolio.invest.application.auth.EmailCodeService.RESET_NEUTRAL_MESSAGE);

        mvc.perform(post("/api/auth/reset-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"ghost\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value(com.portfolio.invest.application.auth.EmailCodeService.RESET_NEUTRAL_MESSAGE));
    }

    @DisplayName("注册成功返回201")
    @Test
    void whenRegister_thenReturn201() throws Exception {
        when(authService.register(any(RegisterCommand.class))).thenReturn(UserView.from(
                User.reconstitute(2L, "alice", "p", UserRole.USER, UserStatus.PENDING, true, NOW, NOW)));

        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"secret-1\","
                                + "\"email\":\"alice@test.local\",\"code\":\"123456\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @DisplayName("注册用户名超长校验失败返回400")
    @Test
    void givenUsernameTooLong_whenRegister_thenReturn400() throws Exception {
        // email/code 补齐让用户名超长成为唯一违例（多违例时 message 取首个 fieldError，顺序不保证）
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + "a".repeat(65) + "\",\"password\":\"secret-1\","
                                + "\"email\":\"alice@test.local\",\"code\":\"123456\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("用户名最长64个字符"));
    }
}
