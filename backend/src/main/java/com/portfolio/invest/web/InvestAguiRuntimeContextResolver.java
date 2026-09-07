package com.portfolio.invest.web;

import com.portfolio.invest.agent.CurrentUserHolder;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.runtime.AguiRuntimeContextRequest;
import io.agentscope.core.agui.runtime.AguiRuntimeContextResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;

@Component
public class InvestAguiRuntimeContextResolver implements AguiRuntimeContextResolver {
    @Override
    public RuntimeContext resolve(AguiRuntimeContextRequest<?> request) {
        Long userId = readUserId(request);
        CurrentUserHolder.set(userId);
        return RuntimeContext.builder().userId(userId == null ? null : userId.toString()).build();
    }
    private Long readUserId(AguiRuntimeContextRequest<?> request) {
        // 2.0.3：getNativeRequest() 无参返回泛型原生请求，不再按类型取
        Object nativeRequest = request.getNativeRequest();
        HttpServletRequest nativeReq = nativeRequest instanceof HttpServletRequest h ? h : null;
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
