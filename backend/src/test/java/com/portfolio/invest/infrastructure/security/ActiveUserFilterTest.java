package com.portfolio.invest.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.invest.domain.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** ActiveUserFilter：公开路径豁免 + 认证上下文异常时直接放行。 */
class ActiveUserFilterTest {

    private final UserRepository repo = mock(UserRepository.class);
    private final ActiveUserFilter filter = new ActiveUserFilter(repo);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);
    private final FilterChain chain = mock(FilterChain.class);

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 公开路径豁免用户状态校验() {
        String[] publicPaths = {
                "/api/auth/login", "/api/auth/register",
                "/api/market/quote", "/api/valuation/overview", "/api/agent/health"
        };
        for (String path : publicPaths) {
            when(request.getServletPath()).thenReturn(path);
            assertThat(filter.shouldNotFilter(request)).as(path).isTrue();
        }

        when(request.getServletPath()).thenReturn("/api/conversations");
        assertThat(filter.shouldNotFilter(request)).isFalse();
    }

    @Test
    void 无认证信息时直接放行且不查库() throws Exception {
        SecurityContextHolder.clearContext();

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(repo);
    }

    @Test
    void 认证未建立时直接放行且不查库() throws Exception {
        // 无 authorities 的构造器 → isAuthenticated() = false
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null));

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(repo);
    }

    @Test
    void 认证主体非AuthenticatedUser时直接放行且不查库() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("anonymous", null, List.of()));

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(repo);
    }
}
