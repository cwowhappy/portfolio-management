package com.portfolio.invest.web;

import com.portfolio.invest.application.allocation.AllocationApplicationService;
import com.portfolio.invest.application.allocation.AssessmentView;
import com.portfolio.invest.application.allocation.CreatePlanCommand;
import com.portfolio.invest.application.allocation.DeviationView;
import com.portfolio.invest.application.allocation.PlanView;
import com.portfolio.invest.application.allocation.QuestionnaireView;
import com.portfolio.invest.application.allocation.SubmitAssessmentCommand;
import com.portfolio.invest.application.allocation.TemplateView;
import com.portfolio.invest.application.allocation.UpdatePlanCommand;
import com.portfolio.invest.application.allocation.WeightView;
import com.portfolio.invest.domain.allocation.AllocationErrorCode;
import com.portfolio.invest.domain.allocation.AllocationException;
import com.portfolio.invest.domain.allocation.AssetClass;
import com.portfolio.invest.domain.allocation.PlanSource;
import com.portfolio.invest.domain.allocation.RiskProfile;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRole;
import com.portfolio.invest.domain.user.UserStatus;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AllocationControllerTest {

    private final AllocationApplicationService service = mock(AllocationApplicationService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AllocationController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private org.springframework.security.core.Authentication auth() {
        var user = User.reconstitute(1L, "u", "p", UserRole.USER, UserStatus.APPROVED, true,
                Instant.now(), Instant.now());
        var principal = new AuthenticatedUser(user);
        return new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());
    }

    @DisplayName("templates返回200")
    @Test
    void whenGetTemplates_thenReturn200() throws Exception {
        when(service.templates()).thenReturn(List.of(
                new TemplateView("BALANCED_60_40", "60/40 股债平衡",
                        List.of(new WeightView(AssetClass.STOCK, new BigDecimal("60"))))));
        mvc.perform(get("/api/allocation/templates").principal(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("60/40 股债平衡"));
    }

    @DisplayName("创建方案返回201")
    @Test
    void whenCreatePlan_thenReturn201() throws Exception {
        when(service.createPlan(eq(1L), any(CreatePlanCommand.class)))
                .thenReturn(new PlanView(5L, "平衡", PlanSource.TEMPLATE,
                        List.of(new WeightView(AssetClass.STOCK, new BigDecimal("60"))), false));
        mvc.perform(post("/api/allocation/plans").principal(auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"平衡\",\"source\":\"TEMPLATE\",\"weights\":[{\"assetClass\":\"STOCK\",\"weight\":60},{\"assetClass\":\"BOND\",\"weight\":40}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("平衡"));
    }

    @DisplayName("更新方案返回200")
    @Test
    void whenUpdatePlan_thenReturn200() throws Exception {
        when(service.updatePlan(eq(1L), eq(5L), any(UpdatePlanCommand.class)))
                .thenReturn(new PlanView(5L, "稳健", PlanSource.CUSTOM,
                        List.of(new WeightView(AssetClass.STOCK, new BigDecimal("40"))), true));
        mvc.perform(put("/api/allocation/plans/5").principal(auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"稳健\",\"weights\":[{\"assetClass\":\"STOCK\",\"weight\":40},{\"assetClass\":\"BOND\",\"weight\":60}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("稳健"));
    }

    @DisplayName("偏离度返回200")
    @Test
    void whenGetDeviation_thenReturn200() throws Exception {
        when(service.deviation(1L)).thenReturn(new DeviationView(List.of(
                new DeviationView.DeviationSlice(AssetClass.STOCK, new BigDecimal("60"),
                        new BigDecimal("70"), new BigDecimal("10")))));
        mvc.perform(get("/api/allocation/deviation").principal(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slices[0].assetClass").value("STOCK"));
    }

    @DisplayName("非本人方案映射404")
    @Test
    void givenOthersPlan_whenGetPlans_thenReturn404() throws Exception {
        when(service.plans(1L)).thenThrow(new AllocationException(AllocationErrorCode.NOT_FOUND, "方案不存在"));
        mvc.perform(get("/api/allocation/plans").principal(auth()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @DisplayName("问卷下发返回200且选项不含分值")
    @Test
    void whenGetQuestionnaire_thenReturn200() throws Exception {
        when(service.questionnaire()).thenReturn(QuestionnaireView.fromBuiltin());
        mvc.perform(get("/api/allocation/assessment/questionnaire").principal(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions.length()").value(8))
                .andExpect(jsonPath("$.questions[0].options[0].text").value("30 岁及以下"))
                .andExpect(jsonPath("$.questions[0].options[0].score").doesNotExist());
    }

    @DisplayName("最新结果有则200")
    @Test
    void whenLatestExists_thenReturn200() throws Exception {
        when(service.latestAssessment(eq(1L))).thenReturn(Optional.of(new AssessmentView(
                24, RiskProfile.BALANCED, "平衡",
                List.of(new WeightView(AssetClass.STOCK, new BigDecimal("50"))),
                Map.of("Q1", "C"), Instant.parse("2026-09-15T00:00:00Z"))));
        mvc.perform(get("/api/allocation/assessment").principal(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile").value("BALANCED"))
                .andExpect(jsonPath("$.profileName").value("平衡"))
                .andExpect(jsonPath("$.totalScore").value(24));
    }

    @DisplayName("最新结果无则204")
    @Test
    void whenLatestMissing_then204() throws Exception {
        when(service.latestAssessment(eq(1L))).thenReturn(Optional.empty());
        mvc.perform(get("/api/allocation/assessment").principal(auth()))
                .andExpect(status().isNoContent());
    }

    @DisplayName("提交答卷返回200")
    @Test
    void whenSubmitAssessment_thenReturn200() throws Exception {
        when(service.submitAssessment(eq(1L), any(SubmitAssessmentCommand.class))).thenReturn(new AssessmentView(
                24, RiskProfile.BALANCED, "平衡",
                List.of(new WeightView(AssetClass.STOCK, new BigDecimal("50"))),
                Map.of("Q1", "C"), Instant.parse("2026-09-15T00:00:00Z")));
        mvc.perform(post("/api/allocation/assessment").principal(auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"questionId\":\"Q1\",\"optionId\":\"C\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileName").value("平衡"));
    }

    @DisplayName("不合法答卷返回400 INVALID_ANSWERS")
    @Test
    void whenInvalidAnswers_then400() throws Exception {
        when(service.submitAssessment(eq(1L), any(SubmitAssessmentCommand.class)))
                .thenThrow(new AllocationException(AllocationErrorCode.INVALID_ANSWERS, "缺少作答: Q5"));
        mvc.perform(post("/api/allocation/assessment").principal(auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"questionId\":\"Q1\",\"optionId\":\"C\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ANSWERS"));
    }
}
