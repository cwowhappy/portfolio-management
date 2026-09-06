package com.portfolio.invest.web;

import com.portfolio.invest.agent.CurrentUserHolder;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.spring.boot.agui.common.AguiRuntimeContextRequest;
import io.agentscope.spring.boot.agui.common.AguiRuntimeContextResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;

@Component
public class InvestAguiRuntimeContextResolver implements AguiRuntimeContextResolver {
    @Override
    public RuntimeContext resolve(AguiRuntimeContextRequest request) {
        Long userId = readUserId(request);
        CurrentUserHolder.set(userId);
        return RuntimeContext.builder().userId(userId == null ? null : userId.toString()).build();
    }
    private Long readUserId(AguiRuntimeContextRequest request) {
        HttpServletRequest nativeReq = request.getNativeRequest(HttpServletRequest.class);
        HttpSession session = nativeReq == null ? null : nativeReq.getSession(false);
        Object ctx = session == null ? null
                : session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (ctx instanceof SecurityContext sc && sc.getAuthentication() != null
                && sc.getAuthentication().getPrincipal() instanceof AuthenticatedUser u) {
            return u.user().id();
        }
        return null;
    }
}
