package com.portfolio.invest.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.application.portfolio.PortfolioApplicationService;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.domain.user.UserRole;
import com.portfolio.invest.domain.user.UserStatus;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import com.portfolio.invest.infrastructure.security.SecurityConfig;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 安全横切切片：在真实 SecurityConfig 过滤器链上验证
 * ActiveUserFilter「停用即时生效」、remember-me 自动认证接线与 logout 行为。
 * 宿主端点选受保护的 /api/portfolio/overview（业务服务打桩）。
 *
 * <p>remember-me 场景里 RememberMeAuthenticationFilter 会把 autoLogin 的结果交给
 * 真实全局 AuthenticationManager 再认证，因此用 UserDetailsService 打桩 +
 * 真实 BCrypt 哈希让 DaoAuthenticationProvider 放行，而不是 mock AuthenticationManager。
 */
@WebMvcTest(PortfolioController.class)
@Import(SecurityConfig.class)
class SecuritySliceTest {

    private static final String PASSWORD = "p";
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder().encode(PASSWORD);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PortfolioApplicationService service;

    // SecurityConfig 装配所需依赖（切片内无真实实现）
    @MockitoBean
    private UserRepository userRepository;
    @MockitoBean
    private UserDetailsService userDetailsService;
    @MockitoBean
    private RememberMeServices rememberMeServices;
    @MockitoBean
    private PersistentTokenRepository persistentTokenRepository;

    private Authentication authOf(User user) {
        var principal = new AuthenticatedUser(user);
        return new UsernamePasswordAuthenticationToken(principal, PASSWORD, principal.getAuthorities());
    }

    private User user(boolean enabled) {
        return User.reconstitute(1L, "u", PASSWORD_HASH, UserRole.USER, UserStatus.APPROVED, enabled,
                Instant.now(), Instant.now());
    }

    @DisplayName("已认证但用户已停用被拦截返回401")
    @Test
    void givenDisabledUser_whenAccessProtectedEndpoint_thenReturn401() throws Exception {
        when(userRepository.findByUsername("u")).thenReturn(Optional.of(user(false)));

        mvc.perform(get("/api/portfolio/overview").with(authentication(authOf(user(false)))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("账号不可用"));
    }

    @DisplayName("已认证但用户已被删除返回401")
    @Test
    void givenDeletedUser_whenAccessProtectedEndpoint_thenReturn401() throws Exception {
        when(userRepository.findByUsername("u")).thenReturn(Optional.empty());

        mvc.perform(get("/api/portfolio/overview").with(authentication(authOf(user(true)))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("账号不可用"));
    }

    @DisplayName("已认证且状态正常放行")
    @Test
    void givenActiveUser_whenAccessProtectedEndpoint_thenReturn200() throws Exception {
        when(userRepository.findByUsername("u")).thenReturn(Optional.of(user(true)));

        mvc.perform(get("/api/portfolio/overview").with(authentication(authOf(user(true)))))
                .andExpect(status().isOk());
    }

    @DisplayName("rememberMe自动认证后放行")
    @Test
    void givenRememberMeAutoLogin_whenAccessProtectedEndpoint_thenReturn200() throws Exception {
        var user = user(true);
        when(rememberMeServices.autoLogin(any(), any())).thenReturn(authOf(user));
        when(userDetailsService.loadUserByUsername("u")).thenReturn(new AuthenticatedUser(user));
        when(userRepository.findByUsername("u")).thenReturn(Optional.of(user));

        mvc.perform(get("/api/portfolio/overview"))
                .andExpect(status().isOk());
    }

    @DisplayName("rememberMe自动认证但用户已停用仍被拦截")
    @Test
    void givenRememberMeAutoLoginWithDisabledUser_whenAccessProtectedEndpoint_thenReturn401() throws Exception {
        var user = user(false);
        when(rememberMeServices.autoLogin(any(), any())).thenReturn(authOf(user));
        when(userDetailsService.loadUserByUsername("u")).thenReturn(new AuthenticatedUser(user));
        when(userRepository.findByUsername("u")).thenReturn(Optional.of(user));

        mvc.perform(get("/api/portfolio/overview"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("账号不可用"));
    }

    @DisplayName("登出返回200并清除会话与rememberMeCookie")
    @Test
    void whenLogout_thenReturn200AndClearSessionAndRememberMeCookie() throws Exception {
        // logout 路径不在 ActiveUserFilter 排除清单内，需打桩用户有效才能走到 LogoutFilter
        when(userRepository.findByUsername("u")).thenReturn(Optional.of(user(true)));

        mvc.perform(post("/api/auth/logout").with(authentication(authOf(user(true)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("已退出登录"))
                .andExpect(cookie().maxAge("JSESSIONID", 0))
                .andExpect(cookie().maxAge(SecurityConfig.REMEMBER_ME_COOKIE, 0));
    }
}
