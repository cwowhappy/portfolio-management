package com.portfolio.invest.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.application.observability.ObservabilityApplicationService;
import com.portfolio.invest.domain.observability.ObservabilityQueryRepository;
import com.portfolio.invest.domain.observability.ObservabilityQueryRepository.DailyLatencyStat;
import com.portfolio.invest.domain.observability.ObservabilityQueryRepository.DailyTokenUsage;
import com.portfolio.invest.domain.observability.ObservabilityQueryRepository.ToolCallStat;
import com.portfolio.invest.domain.observability.ObservabilityQueryRepository.ToolLatencyStat;
import com.portfolio.invest.domain.observability.ObservabilityQueryRepository.ToolTraceRow;
import com.portfolio.invest.domain.observability.ObservabilityQueryRepository.TracePage;
import com.portfolio.invest.domain.observability.ObservabilityQueryRepository.TurnLatencyStat;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.infrastructure.security.ActiveUserStatusCache;
import com.portfolio.invest.infrastructure.security.SecurityConfig;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 可观测性看板查询端点切片（MS-30 B5，设计规格 §7.1）：trace 分页筛选参数透传与
 * {items,page,total} 形状、cost 按日按工具、latency percentile 形状、prompt-assets
 * 版本链（current 标记）+ 安全规则（匿名 401 / 非管理员 403 / ADMIN 放行）。沿
 * UserAdminControllerSliceTest 形态（真实 SecurityConfig + @WithMockUser）。
 */
@WebMvcTest(ObservabilityController.class)
@Import({SecurityConfig.class, ActiveUserStatusCache.class})
class ObservabilityControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ObservabilityApplicationService service;

    // SecurityConfig 装配所需依赖（切片内无真实实现）
    @MockitoBean
    private UserRepository userRepository;
    @MockitoBean
    private UserDetailsService userDetailsService;
    @MockitoBean
    private RememberMeServices rememberMeServices;
    @MockitoBean
    private PersistentTokenRepository persistentTokenRepository;

    private ToolTraceRow traceRow(long id, String tool, boolean failed) {
        return new ToolTraceRow(id, 2L, "conv-1", "msg-" + id, tool,
                "{\"code\":\"600519\"}", "结果文本", 1, "2026-10-09", "TRADE_DATE",
                false, failed, 120L, Instant.parse("2026-10-09T09:31:00Z"));
    }

    @DisplayName("匿名查询trace返回401")
    @Test
    void givenAnonymous_whenGetTrace_then401() throws Exception {
        mvc.perform(get("/api/admin/observability/trace"))
                .andExpect(status().isUnauthorized());
    }

    @DisplayName("非管理员查询cost返回403")
    @Test
    @WithMockUser(roles = "USER")
    void givenNonAdmin_whenGetCost_then403() throws Exception {
        mvc.perform(get("/api/admin/observability/cost"))
                .andExpect(status().isForbidden());
    }

    @DisplayName("管理员带全部筛选查询trace：参数透传且返回items/page/total形状")
    @Test
    @WithMockUser(roles = "ADMIN")
    void givenAdminAndFilters_whenGetTrace_thenFiltersPassedAndPageShapeReturned() throws Exception {
        Instant from = Instant.parse("2026-10-08T00:00:00Z");
        Instant to = Instant.parse("2026-10-10T00:00:00Z");
        when(service.trace(from, to, "get_quote", true, 0, 50))
                .thenReturn(new TracePage(List.of(traceRow(3L, "get_quote", true)), 5));

        mvc.perform(get("/api/admin/observability/trace")
                        .param("from", "2026-10-08T00:00:00Z")
                        .param("to", "2026-10-10T00:00:00Z")
                        .param("tool", "get_quote")
                        .param("failed", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(3))
                .andExpect(jsonPath("$.items[0].toolName").value("get_quote"))
                .andExpect(jsonPath("$.items[0].failed").value(true))
                .andExpect(jsonPath("$.items[0].durationMs").value(120));

        verify(service).trace(from, to, "get_quote", true, 0, 50);
    }

    @DisplayName("trace缺省参数按page=0/size=50无筛选透传")
    @Test
    @WithMockUser(roles = "ADMIN")
    void givenDefaults_whenGetTrace_thenDefaultPagingPassed() throws Exception {
        when(service.trace(null, null, null, null, 0, 50))
                .thenReturn(new TracePage(List.of(), 0));

        mvc.perform(get("/api/admin/observability/trace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.total").value(0));

        verify(service).trace(null, null, null, null, 0, 50);
    }

    @DisplayName("from参数非ISO时间返回400")
    @Test
    @WithMockUser(roles = "ADMIN")
    void givenMalformedFrom_whenGetTrace_then400() throws Exception {
        mvc.perform(get("/api/admin/observability/trace").param("from", "not-an-instant"))
                .andExpect(status().isBadRequest());
    }

    @DisplayName("size越界返回400")
    @Test
    @WithMockUser(roles = "ADMIN")
    void givenZeroSize_whenGetTrace_then400() throws Exception {
        mvc.perform(get("/api/admin/observability/trace").param("size", "0"))
                .andExpect(status().isBadRequest());
    }

    @DisplayName("管理员查询cost返回byDay与byTool形状")
    @Test
    @WithMockUser(roles = "ADMIN")
    void givenAdmin_whenGetCost_thenByDayAndByToolShape() throws Exception {
        when(service.cost(7)).thenReturn(new ObservabilityApplicationService.Cost(
                List.of(new DailyTokenUsage("2026-10-09", 150, 60, 210, 2)),
                List.of(new ToolCallStat("get_quote", 3, 200.0),
                        new ToolCallStat("get_kline", 1, null))));

        mvc.perform(get("/api/admin/observability/cost"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.byDay[0].date").value("2026-10-09"))
                .andExpect(jsonPath("$.byDay[0].promptTokens").value(150))
                .andExpect(jsonPath("$.byDay[0].completionTokens").value(60))
                .andExpect(jsonPath("$.byDay[0].totalTokens").value(210))
                .andExpect(jsonPath("$.byDay[0].turns").value(2))
                .andExpect(jsonPath("$.byTool[0].tool").value("get_quote"))
                .andExpect(jsonPath("$.byTool[0].calls").value(3))
                .andExpect(jsonPath("$.byTool[0].avgDurationMs").value(200.0));

        verify(service).cost(7);
    }

    @DisplayName("days越界返回400")
    @Test
    @WithMockUser(roles = "ADMIN")
    void givenZeroDays_whenGetCost_then400() throws Exception {
        mvc.perform(get("/api/admin/observability/cost").param("days", "0"))
                .andExpect(status().isBadRequest());
    }

    @DisplayName("管理员查询latency返回turn/tool百分位形状")
    @Test
    @WithMockUser(roles = "ADMIN")
    void givenAdmin_whenGetLatency_thenPercentileShape() throws Exception {
        when(service.latency(7)).thenReturn(new ObservabilityApplicationService.Latency(
                new TurnLatencyStat(300.0, 480.0),
                List.of(new DailyLatencyStat("2026-10-09", 300.0, 480.0, 5)),
                List.of(new ToolLatencyStat("get_quote", 200.0, 290.0, 3))));

        mvc.perform(get("/api/admin/observability/latency"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turn.p50Ms").value(300.0))
                .andExpect(jsonPath("$.turn.p95Ms").value(480.0))
                .andExpect(jsonPath("$.turn.byDay[0].date").value("2026-10-09"))
                .andExpect(jsonPath("$.turn.byDay[0].turns").value(5))
                .andExpect(jsonPath("$.tool.byTool[0].tool").value("get_quote"))
                .andExpect(jsonPath("$.tool.byTool[0].p50Ms").value(200.0))
                .andExpect(jsonPath("$.tool.byTool[0].p95Ms").value(290.0))
                .andExpect(jsonPath("$.tool.byTool[0].calls").value(3));
    }

    @DisplayName("管理员查询prompt-assets返回分组版本链且最新版current=true")
    @Test
    @WithMockUser(roles = "ADMIN")
    void givenAdmin_whenGetPromptAssets_thenChainedVersionsWithCurrentFlag() throws Exception {
        when(service.assetChains()).thenReturn(List.of(
                new ObservabilityApplicationService.PromptAssetChain(
                        "SKILL", "skill.tushare_data", List.of(
                                new com.portfolio.invest.domain.eval.PromptAssetVersion(
                                        9L, "SKILL", "skill.tushare_data", 2, "hash-b", null,
                                        Instant.parse("2026-10-09T02:00:00Z")),
                                new com.portfolio.invest.domain.eval.PromptAssetVersion(
                                        4L, "SKILL", "skill.tushare_data", 1, "hash-a", "初版",
                                        Instant.parse("2026-10-01T02:00:00Z"))))));

        mvc.perform(get("/api/admin/prompt-assets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assets[0].assetType").value("SKILL"))
                .andExpect(jsonPath("$.assets[0].assetKey").value("skill.tushare_data"))
                .andExpect(jsonPath("$.assets[0].versions.length()").value(2))
                .andExpect(jsonPath("$.assets[0].versions[0].id").value(9))
                .andExpect(jsonPath("$.assets[0].versions[0].version").value(2))
                .andExpect(jsonPath("$.assets[0].versions[0].contentHash").value("hash-b"))
                .andExpect(jsonPath("$.assets[0].versions[0].note").doesNotExist())
                .andExpect(jsonPath("$.assets[0].versions[0].current").value(true))
                .andExpect(jsonPath("$.assets[0].versions[1].id").value(4))
                .andExpect(jsonPath("$.assets[0].versions[1].current").value(false));
    }
}
