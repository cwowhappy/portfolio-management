package com.portfolio.invest.infrastructure.security;

import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** 每次受保护请求从 DB 校验用户状态：被拒/待审/停用立即 401 并清上下文（停用即时生效）。 */
public class ActiveUserFilter extends OncePerRequestFilter {

    private final UserRepository userRepository;

    public ActiveUserFilter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.equals("/api/auth/login") || path.equals("/api/auth/register")
                || path.startsWith("/api/market/") || path.startsWith("/api/valuation/")
                || path.equals("/api/agent/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof AuthenticatedUser au) {
            User fresh = userRepository.findByUsername(au.getUsername()).orElse(null);
            if (fresh == null || !fresh.canLogin()) {
                SecurityContextHolder.clearContext();
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json; charset=utf-8");
                response.getWriter().write("{\"message\":\"账号不可用\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
