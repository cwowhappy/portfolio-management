package com.portfolio.invest.infrastructure.security;

import com.portfolio.invest.domain.user.UserSessionRegistry;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Component;

/**
 * 会话登记端口实现：委托 Spring Security SessionRegistry。
 *
 * <p>principal 统一用 username 字符串登记（与 expireAll 的查找键一致）；expireNow 仅标记过期，
 * 实际 401 拦截由过滤器链中的 ConcurrentSessionFilter 在后续请求时执行（SecurityConfig B14）。
 */
@Component
public class SpringSessionUserRegistry implements UserSessionRegistry {

    private final SessionRegistry delegate;

    public SpringSessionUserRegistry(SessionRegistry delegate) {
        this.delegate = delegate;
    }

    @Override
    public void register(String sessionId, String username) {
        delegate.registerNewSession(sessionId, username);
    }

    @Override
    public void expireAll(String username) {
        // includeExpired=false：只取在册未过期会话（已过期条目本就拒绝），逐个标记过期
        delegate.getAllSessions(username, false).forEach(info -> info.expireNow());
    }
}
