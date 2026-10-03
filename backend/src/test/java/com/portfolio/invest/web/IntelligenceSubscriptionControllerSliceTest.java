package com.portfolio.invest.web;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.application.intelligence.SubscriptionService;
import com.portfolio.invest.application.intelligence.SubscriptionService.SubscriptionView;
import com.portfolio.invest.application.intelligence.SubscriptionService.UpdateSubscriptionCommand;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRole;
import com.portfolio.invest.domain.user.UserStatus;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import java.time.Instant;
import java.util.ArrayList;
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
 * 订阅双端点切片（/api/intelligence/subscription，F16）：GET 无落库行返回缺省视图
 * （service 打桩）、PUT 校验失败 400 INVALID_REQUEST 走既有 MethodArgumentNotValidException
 * 分支（stock code 空白 / 超 100 上限 / pushEnabled 缺失 / 行业元素空白）且不触达服务、
 * PUT 合法 200 且透传 userId 与命令、保存后 GET 回读。业务编排打桩；安全用 Boot 默认链
 * （需认证 + CSRF 开启），与 IndustryWatchControllerTest 同构——独立前缀天然需登录。
 */
@WebMvcTest(IntelligenceSubscriptionController.class)
class IntelligenceSubscriptionControllerSliceTest {

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
    @DisplayName("GET 无落库行：返回缺省视图（pushEnabled=true、行业/标的空数组、updatedAt 为 null）")
    void givenNoRow_whenGet_thenDefaultView() throws Exception {
        when(service.get(1L)).thenReturn(new SubscriptionView(true, List.of(), List.of(), null));

        mvc.perform(get("/api/intelligence/subscription").with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pushEnabled").value(true))
                .andExpect(jsonPath("$.industries", empty()))
                .andExpect(jsonPath("$.stocks", empty()))
                .andExpect(jsonPath("$.updatedAt", nullValue()));
    }

    @Test
    @DisplayName("GET 已存订阅：回读 pushEnabled/行业/标的对（code、name）与 updatedAt")
    void givenSavedSubscription_whenGet_thenRoundTripsFields() throws Exception {
        when(service.get(1L)).thenReturn(new SubscriptionView(false, List.of("801010"),
                List.of(new SubscriptionView.StockView("600519", "贵州茅台")),
                Instant.parse("2026-10-01T12:00:00Z")));

        mvc.perform(get("/api/intelligence/subscription").with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pushEnabled").value(false))
                .andExpect(jsonPath("$.industries[0]").value("801010"))
                .andExpect(jsonPath("$.stocks[0].code").value("600519"))
                .andExpect(jsonPath("$.stocks[0].name").value("贵州茅台"))
                .andExpect(jsonPath("$.updatedAt").value("2026-10-01T12:00:00Z"));
    }

    @Test
    @DisplayName("PUT 合法命令：返回 200 保存后视图且透传 userId 与命令")
    void givenValidCommand_whenPut_then200WithSavedViewAndDelegation() throws Exception {
        var cmd = new UpdateSubscriptionCommand(false, List.of("801010"),
                List.of(new UpdateSubscriptionCommand.StockItem("600519", "贵州茅台")));
        var saved = new SubscriptionView(false, List.of("801010"),
                List.of(new SubscriptionView.StockView("600519", "贵州茅台")),
                Instant.parse("2026-10-01T12:00:00Z"));
        when(service.update(1L, cmd)).thenReturn(saved);

        mvc.perform(put("/api/intelligence/subscription").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pushEnabled": false,
                                 "industries": ["801010"],
                                 "stocks": [{"code": "600519", "name": "贵州茅台"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pushEnabled").value(false))
                .andExpect(jsonPath("$.stocks[0].code").value("600519"))
                .andExpect(jsonPath("$.updatedAt").value("2026-10-01T12:00:00Z"));

        verify(service).update(1L, cmd);
    }

    @Test
    @DisplayName("PUT 后再 GET：回读同一保存视图（前端表单整体提交→回显闭环）")
    void givenPutSaved_whenGetAgain_thenSameViewReturned() throws Exception {
        var saved = new SubscriptionView(true, List.of("801780"),
                List.of(new SubscriptionView.StockView("300750", "宁德时代")),
                Instant.parse("2026-10-01T12:00:00Z"));
        when(service.get(1L)).thenReturn(saved);

        mvc.perform(get("/api/intelligence/subscription").with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pushEnabled").value(true))
                .andExpect(jsonPath("$.industries[0]").value("801780"))
                .andExpect(jsonPath("$.stocks[0].code").value("300750"))
                .andExpect(jsonPath("$.stocks[0].name").value("宁德时代"));
    }

    @Test
    @DisplayName("PUT 标的 code 空白：400 INVALID_REQUEST 且不触达服务")
    void givenBlankStockCode_whenPut_then400InvalidRequest() throws Exception {
        mvc.perform(put("/api/intelligence/subscription").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pushEnabled": true,
                                 "stocks": [{"code": "  ", "name": "贵州茅台"}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("PUT 标的数超 100 上限：400 INVALID_REQUEST 且不触达服务")
    void given101Stocks_whenPut_then400InvalidRequest() throws Exception {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            items.add("{\"code\": \"6000" + String.format("%03d", i) + "\"}");
        }

        mvc.perform(put("/api/intelligence/subscription").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pushEnabled\": true, \"stocks\": [" + String.join(",", items) + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("PUT pushEnabled 缺失：400 INVALID_REQUEST 且不触达服务")
    void givenMissingPushEnabled_whenPut_then400InvalidRequest() throws Exception {
        mvc.perform(put("/api/intelligence/subscription").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"industries\": [\"801010\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("PUT 行业元素空白：400 INVALID_REQUEST 且不触达服务（容器元素约束）")
    void givenBlankIndustryElement_whenPut_then400InvalidRequest() throws Exception {
        mvc.perform(put("/api/intelligence/subscription").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pushEnabled\": true, \"industries\": [\"  \"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("未登录访问双端点均 401（独立前缀不在公开白名单）")
    void givenAnonymous_whenAccessBothEndpoints_then401() throws Exception {
        mvc.perform(get("/api/intelligence/subscription"))
                .andExpect(status().isUnauthorized());

        mvc.perform(put("/api/intelligence/subscription").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pushEnabled\": true}"))
                .andExpect(status().isUnauthorized());
    }
}
