package com.portfolio.invest.web;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.agent.report.ReportWorkflowOrchestrator;
import com.portfolio.invest.application.research.ReportApplicationService;
import com.portfolio.invest.application.research.ReportApplicationService.StartTaskResult;
import com.portfolio.invest.application.research.ReportQuotaExceededException;
import com.portfolio.invest.application.research.ResearchViews.ReportDetailView;
import com.portfolio.invest.application.research.ResearchViews.ReportDetailView.AnchorView;
import com.portfolio.invest.application.research.ResearchViews.ReportDetailView.AssumptionView;
import com.portfolio.invest.application.research.ResearchViews.ReportSummaryView;
import com.portfolio.invest.application.research.ResearchViews.TaskStatusView;
import com.portfolio.invest.domain.research.ResearchErrorCode;
import com.portfolio.invest.domain.research.ResearchException;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRole;
import com.portfolio.invest.domain.user.UserStatus;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 研报任务五端点切片（MS-32 B13，设计规格 §3.12）：发起 202（taskId+quotaRemaining，
 * 编排器 submit 以 taskId 接线）/ 轮询与列表 / 详情（assumptions[].anchor.state）/
 * 确认分派（CONFIRM|REJECT）+ 异常映射（配额 402 带 remaining、活跃任务 409、
 * 非待确认 409、他人 404、非法 action 与缺 reason 400——B2 Minor-4 域校验优先序）。
 */
class ReportTaskControllerTest {

    /** 复刻 Spring Boot 对 ObjectMapper 的配置（IndustryControllerTest 同款）：Instant 序列化为 ISO 串。 */
    private static final ObjectMapper BOOT_LIKE_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final ReportApplicationService service = mock(ReportApplicationService.class);
    private final ReportWorkflowOrchestrator orchestrator = mock(ReportWorkflowOrchestrator.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ReportTaskController(service, orchestrator))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(BOOT_LIKE_MAPPER))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private Authentication auth() {
        var user = User.reconstitute(1L, "u", "p", UserRole.USER, UserStatus.APPROVED, true,
                Instant.now(), Instant.now());
        var principal = new AuthenticatedUser(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    @DisplayName("发起研报返回202并接线编排器")
    @Test
    void givenQuotaAvailable_whenStartTask_then202AndOrchestratorSubmitted() throws Exception {
        when(service.startTask(1L, 5L)).thenReturn(new StartTaskResult(7L, 86000L));

        mvc.perform(post("/api/research/projects/5/report-tasks").principal(auth()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").value(7))
                .andExpect(jsonPath("$.quotaRemaining").value(86000));

        verify(orchestrator).submit(7L, 1L, 5L);
    }

    @DisplayName("日配额耗尽映射402且remaining随体透出")
    @Test
    void givenDailyQuotaExhausted_whenStartTask_then402WithRemaining() throws Exception {
        when(service.startTask(1L, 5L))
                .thenThrow(new ReportQuotaExceededException("当日研报 token 配额已用尽，次日恢复", 0L));

        mvc.perform(post("/api/research/projects/5/report-tasks").principal(auth()))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("REPORT_QUOTA_EXCEEDED"))
                .andExpect(jsonPath("$.remaining").value(0));

        verifyNoInteractions(orchestrator);
    }

    @DisplayName("同项目已有活跃任务映射409")
    @Test
    void givenActiveTaskExists_whenStartTask_then409() throws Exception {
        when(service.startTask(1L, 5L)).thenThrow(new ResearchException(
                ResearchErrorCode.TASK_ACTIVE_EXISTS, "该项目已有进行中的研报任务"));

        mvc.perform(post("/api/research/projects/5/report-tasks").principal(auth()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_ACTIVE_EXISTS"));

        verifyNoInteractions(orchestrator);
    }

    @DisplayName("他人项目发起映射404")
    @Test
    void givenOthersProject_whenStartTask_then404() throws Exception {
        when(service.startTask(1L, 99L))
                .thenThrow(new ResearchException(ResearchErrorCode.NOT_FOUND, "研究项目不存在"));

        mvc.perform(post("/api/research/projects/99/report-tasks").principal(auth()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        verifyNoInteractions(orchestrator);
    }

    @DisplayName("轮询最新任务返回状态视图（SUCCEEDED 回连 reportId）")
    @Test
    void givenSucceededTask_whenGetLatest_thenReturnStatusView() throws Exception {
        when(service.latestTask(1L, 5L))
                .thenReturn(new TaskStatusView("SUCCEEDED", "VALIDATING", 4321L, null, null, 9L));

        mvc.perform(get("/api/research/projects/5/report-tasks/latest").principal(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.stage").value("VALIDATING"))
                .andExpect(jsonPath("$.tokenUsage").value(4321))
                .andExpect(jsonPath("$.errorCode").doesNotExist())
                .andExpect(jsonPath("$.reportId").value(9));
    }

    @DisplayName("轮询他人项目映射404")
    @Test
    void givenOthersProject_whenGetLatest_then404() throws Exception {
        when(service.latestTask(1L, 99L))
                .thenThrow(new ResearchException(ResearchErrorCode.NOT_FOUND, "研报任务不存在"));

        mvc.perform(get("/api/research/projects/99/report-tasks/latest").principal(auth()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @DisplayName("项目研报列表返回摘要（createdAt 倒序由服务保证）")
    @Test
    void givenProjectReports_whenListReports_thenReturnSummaries() throws Exception {
        when(service.reports(1L, 5L)).thenReturn(List.of(
                new ReportSummaryView(9L, "贵州茅台深度研报：三重假设与验证", "PENDING_CONFIRM",
                        Instant.parse("2026-10-11T02:03:04Z")),
                new ReportSummaryView(3L, "贵州茅台研报（旧版）", "REJECTED",
                        Instant.parse("2026-09-28T02:03:04Z"))));

        mvc.perform(get("/api/research/projects/5/reports").principal(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(9))
                .andExpect(jsonPath("$[0].title").value("贵州茅台深度研报：三重假设与验证"))
                .andExpect(jsonPath("$[0].status").value("PENDING_CONFIRM"))
                .andExpect(jsonPath("$[1].status").value("REJECTED"));
    }

    @DisplayName("研报详情返回假设条目与锚点重查状态")
    @Test
    void givenReportDetail_whenGetReport_thenAnchorStateIncluded() throws Exception {
        when(service.reportDetail(1L, 9L)).thenReturn(new ReportDetailView(9L, 5L,
                "贵州茅台深度研报：三重假设与验证", "PENDING_CONFIRM",
                "# 贵州茅台深度研报\n\n正文……", "数据截至 2026-10-10 收盘",
                Instant.parse("2026-10-11T02:03:04Z"), Instant.parse("2026-10-11T06:03:04Z"),
                List.of(
                        new AssumptionView(1, "2026 年白酒行业动销回暖",
                                new AnchorView("get_kline_daily",
                                        Map.of("symbol", "600519", "period", "daily"),
                                        Instant.parse("2026-10-10T07:00:00Z"), "FRESH", "1580.00")),
                        new AssumptionView(2, "批价企稳回升",
                                new AnchorView("get_quote",
                                        Map.of("symbol", "600519"),
                                        Instant.parse("2026-09-30T07:00:00Z"), "STALE", null)))));

        mvc.perform(get("/api/research/reports/9").principal(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(9))
                .andExpect(jsonPath("$.contentMd").value("# 贵州茅台深度研报\n\n正文……"))
                .andExpect(jsonPath("$.stalenessCheckedAt").value("2026-10-11T06:03:04Z"))
                .andExpect(jsonPath("$.assumptions[0].seq").value(1))
                .andExpect(jsonPath("$.assumptions[0].anchor.state").value("FRESH"))
                .andExpect(jsonPath("$.assumptions[0].anchor.currentValue").value("1580.00"))
                .andExpect(jsonPath("$.assumptions[1].anchor.state").value("STALE"))
                .andExpect(jsonPath("$.assumptions[1].anchor.currentValue").doesNotExist());
    }

    @DisplayName("CONFIRM 分派确认并返回定稿摘要")
    @Test
    void givenConfirmAction_whenConfirm_thenReturnConfirmedSummary() throws Exception {
        when(service.confirm(1L, 9L)).thenReturn(new ReportSummaryView(9L,
                "贵州茅台深度研报：三重假设与验证", "CONFIRMED",
                Instant.parse("2026-10-11T02:03:04Z")));

        mvc.perform(post("/api/research/reports/9/confirm").principal(auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"CONFIRM\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        verify(service).confirm(1L, 9L);
        verify(service, never()).reject(anyLong(), anyLong(), anyString());
    }

    @DisplayName("REJECT 分派拒绝并透传 reason")
    @Test
    void givenRejectWithReason_whenConfirm_thenReturnRejectedSummary() throws Exception {
        when(service.reject(1L, 9L, "假设2批价数据已失效")).thenReturn(new ReportSummaryView(9L,
                "贵州茅台深度研报：三重假设与验证", "REJECTED",
                Instant.parse("2026-10-11T02:03:04Z")));

        mvc.perform(post("/api/research/reports/9/confirm").principal(auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"REJECT\",\"reason\":\"假设2批价数据已失效\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        verify(service).reject(1L, 9L, "假设2批价数据已失效");
        verify(service, never()).confirm(anyLong(), anyLong());
    }

    @DisplayName("action 缺失映射400")
    @Test
    void givenBodyWithoutAction_whenConfirm_then400() throws Exception {
        mvc.perform(post("/api/research/reports/9/confirm").principal(auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(service);
    }

    @DisplayName("action 非 CONFIRM/REJECT 映射400")
    @Test
    void givenInvalidAction_whenConfirm_then400() throws Exception {
        mvc.perform(post("/api/research/reports/9/confirm").principal(auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"APPROVE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(service);
    }

    @DisplayName("终态研报REJECT空白reason映射400而非409（B2 Minor-4 域校验优先序）")
    @Test
    void givenTerminalReportAndBlankReason_whenReject_then400Not409() throws Exception {
        when(service.reject(1L, 9L, "")).thenThrow(new ResearchException(
                ResearchErrorCode.REJECT_REASON_REQUIRED, "拒绝理由必填"));

        mvc.perform(post("/api/research/reports/9/confirm").principal(auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"REJECT\",\"reason\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REJECT_REASON_REQUIRED"));
    }

    @DisplayName("非待确认态确认映射409")
    @Test
    void givenNonPendingReport_whenConfirm_then409() throws Exception {
        when(service.confirm(1L, 9L)).thenThrow(new ResearchException(
                ResearchErrorCode.REPORT_INVALID_STATE, "研报不在待确认状态"));

        mvc.perform(post("/api/research/reports/9/confirm").principal(auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"CONFIRM\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REPORT_INVALID_STATE"));
    }
}
