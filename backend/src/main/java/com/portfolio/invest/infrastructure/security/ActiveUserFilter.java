package com.portfolio.invest.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 每次受保护请求校验用户状态：被拒/待审/停用立即 401 并清上下文（停用即时生效）。
 * 判定走 {@link ActiveUserStatusCache} 短 TTL 缓存（60s 复用 + 状态变更事件逐出），
 * 正常流量不每请求查库。
 */
public class ActiveUserFilter extends OncePerRequestFilter {

    private final ActiveUserStatusCache statusCache;

    public ActiveUserFilter(ActiveUserStatusCache statusCache) {
        this.statusCache = statusCache;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 与 SecurityConfig.permitAll 共用同一份公开端点清单（见 PublicEndpointPaths）
        return PublicEndpointPaths.isPublicPath(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof AuthenticatedUser au) {
            if (!statusCache.isActive(au.getUsername())) {
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
