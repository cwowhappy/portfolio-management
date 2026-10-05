package com.portfolio.invest.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.domain.user.UserRole;
import com.portfolio.invest.domain.user.UserStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** ActiveUserFilter：公开路径豁免 + 认证上下文异常时直接放行；状态判定走短 TTL 缓存。 */
class ActiveUserFilterTest {

    private final UserRepository repo = mock(UserRepository.class);
    private final ActiveUserFilter filter = new ActiveUserFilter(new ActiveUserStatusCache(repo));
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);
    private final FilterChain chain = mock(FilterChain.class);

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @DisplayName("公开路径豁免用户状态校验")
    @Test
    void givenPublicPath_whenShouldNotFilter_thenExemptsFromStatusCheck() {
        String[] publicPaths = {
                "/api/auth/login", "/api/auth/register", "/api/auth/register-code",
                "/api/auth/reset-code", "/api/auth/reset-password",
                "/api/market/quote", "/api/valuation/overview", "/api/agent/health",
                "/api/agent/status", "/api/screening/stocks", "/actuator/health"
        };
        for (String path : publicPaths) {
            when(request.getServletPath()).thenReturn(path);
            assertThat(filter.shouldNotFilter(request)).as(path).isTrue();
        }

        when(request.getServletPath()).thenReturn("/api/conversations");
        assertThat(filter.shouldNotFilter(request)).isFalse();
    }

    @DisplayName("无认证信息时直接放行且不查库")
    @Test
    void givenNoAuthentication_whenDoFilterInternal_thenPassesThroughWithoutQuerying() throws Exception {
        SecurityContextHolder.clearContext();

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(repo);
    }

    @DisplayName("认证未建立时直接放行且不查库")
    @Test
    void givenAuthenticationNotEstablished_whenDoFilterInternal_thenPassesThroughWithoutQuerying() throws Exception {
        // 无 authorities 的构造器 → isAuthenticated() = false
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null));

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(repo);
    }

    @DisplayName("认证主体非AuthenticatedUser时直接放行且不查库")
    @Test
    void givenPrincipalNotAuthenticatedUser_whenDoFilterInternal_thenPassesThroughWithoutQuerying() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("anonymous", null, List.of()));

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(repo);
    }

    @DisplayName("认证用户可用时放行")
    @Test
    void givenActiveAuthenticatedUser_whenDoFilterInternal_thenPasses() throws Exception {
        User active = User.reconstitute(1L, "alice", "h", UserRole.USER, UserStatus.APPROVED, true, null, null);
        authenticateAs(active);
        when(repo.findByUsername("alice")).thenReturn(Optional.of(active));

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(repo, times(1)).findByUsername("alice");
    }

    @DisplayName("认证用户停用时401并写账号不可用")
    @Test
    void givenDisabledAuthenticatedUser_whenDoFilterInternal_then401WithMessage() throws Exception {
        User disabled = User.reconstitute(1L, "alice", "h", UserRole.USER, UserStatus.APPROVED, false, null, null);
        authenticateAs(disabled);
        when(repo.findByUsername("alice")).thenReturn(Optional.of(disabled));
        StringWriter body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(response).setContentType("application/json; charset=utf-8");
        assertThat(body.toString()).isEqualTo("{\"message\":\"账号不可用\"}");
        verify(chain, never()).doFilter(request, response);
    }

    @DisplayName("认证用户已不存在时401")
    @Test
    void givenDeletedAuthenticatedUser_whenDoFilterInternal_then401() throws Exception {
        User gone = User.reconstitute(1L, "alice", "h", UserRole.USER, UserStatus.APPROVED, true, null, null);
        authenticateAs(gone);
        when(repo.findByUsername("alice")).thenReturn(Optional.empty());
        StringWriter body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(request, response);
    }

    private void authenticateAs(User user) {
        AuthenticatedUser principal = new AuthenticatedUser(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
