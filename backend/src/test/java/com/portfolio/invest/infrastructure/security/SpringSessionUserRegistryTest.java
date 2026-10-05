package com.portfolio.invest.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.domain.user.UserSessionRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.session.SessionRegistryImpl;

/**
 * B14：UserSessionRegistry 端口映射单元测试——register→registerNewSession、
 * expireAll→该用户全部在册会话过期、其他用户不受影响。
 */
class SpringSessionUserRegistryTest {

    private final SessionRegistryImpl delegate = new SessionRegistryImpl();
    private final UserSessionRegistry registry = new SpringSessionUserRegistry(delegate);

    @DisplayName("expireAll 使该用户全部会话过期，其他用户不受影响")
    @Test
    void givenRegisteredSessions_whenExpireAll_thenOnlyThatUsersSessionsExpired() {
        registry.register("sess-a1", "alice");
        registry.register("sess-a2", "alice");
        registry.register("sess-b1", "bob");

        registry.expireAll("alice");

        assertThat(delegate.getSessionInformation("sess-a1")).isNotNull();
        assertThat(delegate.getSessionInformation("sess-a1").isExpired()).isTrue();
        assertThat(delegate.getSessionInformation("sess-a2").isExpired()).isTrue();
        assertThat(delegate.getSessionInformation("sess-b1")).isNotNull();
        assertThat(delegate.getSessionInformation("sess-b1").isExpired()).isFalse();
    }

    @DisplayName("expireAll 后再登录登记新会话可用（不携带过期标记）")
    @Test
    void givenExpiredSessions_whenRegisterNewSession_thenFreshSessionNotExpired() {
        registry.register("sess-old", "alice");
        registry.expireAll("alice");

        registry.register("sess-new", "alice");

        assertThat(delegate.getSessionInformation("sess-new").isExpired()).isFalse();
    }
}
