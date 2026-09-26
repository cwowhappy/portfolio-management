package com.portfolio.invest.web;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.application.industry.IndustryWatchApplicationService;
import com.portfolio.invest.application.industry.IndustryWatchView;
import com.portfolio.invest.domain.industry.IndustryErrorCode;
import com.portfolio.invest.domain.industry.IndustryException;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRole;
import com.portfolio.invest.domain.user.UserStatus;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 行业关注三端点切片：GET 列表 / POST 幂等关注（未知行业 404，INDUSTRY_NOT_FOUND
 * 复用既有 GlobalExceptionHandler 映射）/ DELETE 幂等取关。业务编排打桩；安全用
 * Boot 默认链（需认证 + CSRF 开启），与 {@link PortfolioImportControllerTest} 同构
 * ——/api/industry-watch 独立前缀不落在 /api/industry/ 公开前缀内，天然需登录。
 */
@WebMvcTest(IndustryWatchController.class)
class IndustryWatchControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private IndustryWatchApplicationService service;

    /** 构造已认证主体：控制器 currentUserId(auth) 会 cast auth.getPrincipal() 为 AuthenticatedUser。 */
    private Authentication auth() {
        var user = User.reconstitute(1L, "u", "p", UserRole.USER, UserStatus.APPROVED, true,
                Instant.now(), Instant.now());
        var principal = new AuthenticatedUser(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    @Test
    @DisplayName("GET 列表返回 200 与关注数组（industryCode/addedAt）")
    void givenWatchedIndustries_whenList_thenReturnJsonArray() throws Exception {
        when(service.listWatched(1L)).thenReturn(List.of(
                new IndustryWatchView("801780", Instant.parse("2026-09-20T00:00:00Z"))));

        mvc.perform(get("/api/industry-watch").with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].industryCode").value("801780"))
                .andExpect(jsonPath("$[0].addedAt").value("2026-09-20T00:00:00Z"));
    }

    @Test
    @DisplayName("POST 关注返回 204 且透传 userId 与 industryCode")
    void givenValidCode_whenWatch_then204AndDelegate() throws Exception {
        mvc.perform(post("/api/industry-watch").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"industryCode\":\"801780\"}"))
                .andExpect(status().isNoContent());

        verify(service).watch(1L, "801780");
    }

    @Test
    @DisplayName("POST 未知行业码返回 404 INDUSTRY_NOT_FOUND")
    void givenUnknownIndustry_whenWatch_then404IndustryNotFound() throws Exception {
        doThrow(new IndustryException(IndustryErrorCode.INDUSTRY_NOT_FOUND, "行业不存在: 999999"))
                .when(service).watch(1L, "999999");

        mvc.perform(post("/api/industry-watch").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"industryCode\":\"999999\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INDUSTRY_NOT_FOUND"));
    }

    @Test
    @DisplayName("POST 空白行业码返回 400（@NotBlank）且不触达服务")
    void givenBlankCode_whenWatch_then400InvalidRequest() throws Exception {
        mvc.perform(post("/api/industry-watch").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"industryCode\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("DELETE 取关返回 204（幂等）且透传路径行业码")
    void givenWatchedIndustry_whenUnwatch_then204AndDelegate() throws Exception {
        mvc.perform(delete("/api/industry-watch/801780").with(authentication(auth())).with(csrf()))
                .andExpect(status().isNoContent());

        verify(service).unwatch(1L, "801780");
    }

    @Test
    @DisplayName("未登录访问三端点均 401（/api/industry-watch 不在公开前缀）")
    void givenAnonymous_whenAccessThreeEndpoints_then401() throws Exception {
        mvc.perform(get("/api/industry-watch"))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/industry-watch").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"industryCode\":\"801780\"}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(delete("/api/industry-watch/801780").with(csrf()))
                .andExpect(status().isUnauthorized());
    }
}
