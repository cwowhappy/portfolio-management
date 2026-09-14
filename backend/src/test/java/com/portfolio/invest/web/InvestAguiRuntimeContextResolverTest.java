package com.portfolio.invest.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.invest.agent.CurrentUserHolder;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRole;
import com.portfolio.invest.domain.user.UserStatus;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.runtime.AguiRuntimeContextRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

/** InvestAguiRuntimeContextResolver：从 session 安全上下文解析 userId 写入 holder，匿名链路落 null。 */
class InvestAguiRuntimeContextResolverTest {

    private InvestAguiRuntimeContextResolver resolver;
    private AguiRuntimeContextRequest<HttpServletRequest> aguiRequest;

    @BeforeEach
    @SuppressWarnings("unchecked") // mock(AguiRuntimeContextRequest.class) 返回原始类型，按原生请求为 HttpServletRequest 具体化
    void setUp() {
        resolver = new InvestAguiRuntimeContextResolver();
        aguiRequest = mock(AguiRuntimeContextRequest.class);
    }

    /** resolver 会写入 CurrentUserHolder，每测试后清理防串扰（含失败路径）。 */
    @AfterEach
    void cleanUp() {
        CurrentUserHolder.remove();
    }

    @DisplayName("认证session → holder与RuntimeContext均带userId")
    @Test
    void givenAuthenticatedSession_whenResolve_thenUserIdInHolderAndContext() {
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        AuthenticatedUser principal = authenticatedUser(7L);
        securityContext.setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        HttpSession session = mock(HttpSession.class);
        when(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .thenReturn(securityContext);
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        when(httpRequest.getSession(false)).thenReturn(session);
        when(aguiRequest.getNativeRequest()).thenReturn(httpRequest);

        RuntimeContext context = resolver.resolve(aguiRequest);

        assertThat(CurrentUserHolder.get()).isEqualTo(7L);
        assertThat(context.getUserId()).isEqualTo("7");
    }

    @DisplayName("匿名或无session → holder与RuntimeContext的userId均为null")
    @Test
    void givenAnonymousOrNoSession_whenResolve_thenUserIdNull() {
        // ① 未建立 session：getSession(false) 返回 null
        HttpServletRequest noSessionRequest = mock(HttpServletRequest.class);
        when(noSessionRequest.getSession(false)).thenReturn(null);
        when(aguiRequest.getNativeRequest()).thenReturn(noSessionRequest);

        RuntimeContext withoutSession = resolver.resolve(aguiRequest);

        assertThat(CurrentUserHolder.get()).isNull();
        assertThat(withoutSession.getUserId()).isNull();

        // ② session 存在但无 SPRING_SECURITY_CONTEXT_KEY（匿名 session）
        HttpSession anonymousSession = mock(HttpSession.class);
        when(anonymousSession.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .thenReturn(null);
        HttpServletRequest anonymousRequest = mock(HttpServletRequest.class);
        when(anonymousRequest.getSession(false)).thenReturn(anonymousSession);
        when(aguiRequest.getNativeRequest()).thenReturn(anonymousRequest);

        RuntimeContext withoutAuth = resolver.resolve(aguiRequest);

        assertThat(CurrentUserHolder.get()).isNull();
        assertThat(withoutAuth.getUserId()).isNull();
    }

    /** 构造真实认证主体：领域 User reconstitute + AuthenticatedUser 包装（避免深层 mock）。 */
    private AuthenticatedUser authenticatedUser(Long id) {
        User user = User.reconstitute(id, "alice", "hash", UserRole.USER, UserStatus.APPROVED,
                true, Instant.now(), Instant.now());
        return new AuthenticatedUser(user);
    }
}
