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
import com.portfolio.invest.application.auth.RegisterCommand;
import com.portfolio.invest.application.auth.UserView;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.domain.user.UserRole;
import com.portfolio.invest.domain.user.UserStatus;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import com.portfolio.invest.infrastructure.security.SecurityConfig;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.Optional;
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
@Import(SecurityConfig.class)
class AuthControllerSliceTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuthApplicationService authService;
    @MockitoBean
    private AuthenticationManager authenticationManager;
    @MockitoBean
    private RememberMeServices rememberMeServices;

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

    private void 认证通过(User user) {
        when(authenticationManager.authenticate(any())).thenReturn(authOf(user));
    }

    @Test
    void 登录成功且rememberMe为true下发rememberMeCookie() throws Exception {
        认证通过(user(UserStatus.APPROVED, true));
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

    @Test
    void 登录成功且rememberMe缺省不下发rememberMeCookie() throws Exception {
        认证通过(user(UserStatus.APPROVED, true));

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"u\",\"password\":\"p\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("u"));

        verify(rememberMeServices, never()).loginSuccess(any(), any(), any());
    }

    @Test
    void 待审核账号登录返回403() throws Exception {
        认证通过(user(UserStatus.PENDING, true));

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"u\",\"password\":\"p\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_PENDING"));
    }

    @Test
    void 已拒绝账号登录返回403() throws Exception {
        认证通过(user(UserStatus.REJECTED, true));

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"u\",\"password\":\"p\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_REJECTED"));
    }

    @Test
    void 已停用账号登录返回403() throws Exception {
        认证通过(user(UserStatus.APPROVED, false));

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"u\",\"password\":\"p\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
    }

    @Test
    void 密码错误返回401() throws Exception {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("bad credentials"));

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"u\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
    }

    @Test
    void 登录用户名为空校验失败返回400() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"p\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("用户名不能为空"));
    }

    @Test
    void me已认证返回当前用户() throws Exception {
        when(userRepository.findByUsername("u")).thenReturn(Optional.of(user(UserStatus.APPROVED, true)));

        mvc.perform(get("/api/auth/me").with(authentication(authOf(user(UserStatus.APPROVED, true)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("u"))
                .andExpect(jsonPath("$.createdAt").value("2026-08-01T02:03:04Z"));
    }

    @Test
    void me未认证返回401() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void me认证主体非AuthenticatedUser时返回401() throws Exception {
        // 认证已建立但主体不是本系统的 AuthenticatedUser（如匿名/其他机制）→ 未登录
        mvc.perform(get("/api/auth/me").with(authentication(
                        new UsernamePasswordAuthenticationToken("anonymous", null, java.util.List.of()))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void 注册成功返回201() throws Exception {
        when(authService.register(any(RegisterCommand.class))).thenReturn(UserView.from(
                User.reconstitute(2L, "alice", "p", UserRole.USER, UserStatus.PENDING, true, NOW, NOW)));

        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"secret-1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void 注册用户名超长校验失败返回400() throws Exception {
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + "a".repeat(65) + "\",\"password\":\"secret-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("用户名最长64个字符"));
    }
}
