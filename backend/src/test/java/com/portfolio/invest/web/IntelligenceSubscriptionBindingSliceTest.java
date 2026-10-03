package com.portfolio.invest.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.application.intelligence.SubscriptionService;
import com.portfolio.invest.application.intelligence.SubscriptionService.BindingCodeView;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRole;
import com.portfolio.invest.domain.user.UserStatus;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 绑定双端点切片（/api/intelligence/subscription/binding*，D8）：生成码 201 +
 * BindingCodeView(code, expiresAt) 回执、解绑幂等 204、未登录 401、写动词缺 CSRF
 * token 403（Boot 默认安全链，与 IntelligenceSubscriptionControllerSliceTest 同构）。
 * 生成/解绑编排打桩——码格式/TTL/冲突重试在 SubscriptionServiceTest 单测收口。
 */
@WebMvcTest(IntelligenceSubscriptionController.class)
class IntelligenceSubscriptionBindingSliceTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SubscriptionService service;

    /** 构造已认证主体：控制器 currentUserId(auth) 会 cast auth.getPrincipal() 为 AuthenticatedUser。 */
    private Authentication auth() {
        var user = User.reconstitute(1L, "u", "p", UserRole.USER, UserStatus.APPROVED, true,
                Instant.now(), Instant.now());
        var principal = new AuthenticatedUser(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    @Test
    @DisplayName("POST /binding-code：201 + 码与失效时刻回执，且透传当前 userId")
    void givenAuthenticated_whenCreateBindingCode_then201WithView() throws Exception {
        when(service.generateCode(1L)).thenReturn(
                new BindingCodeView("482913", Instant.parse("2026-10-03T09:10:00Z")));

        mvc.perform(post("/api/intelligence/subscription/binding-code")
                        .with(authentication(auth())).with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("482913"))
                .andExpect(jsonPath("$.expiresAt").value("2026-10-03T09:10:00Z"));

        verify(service).generateCode(1L);
    }

    @Test
    @DisplayName("DELETE /binding：解绑 204（幂等——未绑定同样 204）且透传当前 userId")
    void givenAuthenticated_whenUnbind_then204AndDelegation() throws Exception {
        mvc.perform(delete("/api/intelligence/subscription/binding")
                        .with(authentication(auth())).with(csrf()))
                .andExpect(status().isNoContent());

        verify(service).unbind(1L);
    }

    @Test
    @DisplayName("未登录访问绑定双端点均 401")
    void givenAnonymous_whenAccessBindingEndpoints_then401() throws Exception {
        mvc.perform(post("/api/intelligence/subscription/binding-code").with(csrf()))
                .andExpect(status().isUnauthorized());

        mvc.perform(delete("/api/intelligence/subscription/binding").with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("登录但缺 CSRF token 的写请求：403（Boot 默认链 CSRF 开启）")
    void givenMissingCsrf_whenCreateBindingCode_then403() throws Exception {
        mvc.perform(post("/api/intelligence/subscription/binding-code")
                        .with(authentication(auth())))
                .andExpect(status().isForbidden());
    }
}
