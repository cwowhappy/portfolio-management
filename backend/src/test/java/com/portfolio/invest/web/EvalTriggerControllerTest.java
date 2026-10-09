package com.portfolio.invest.web;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.application.eval.EvalAdminApplicationService;
import com.portfolio.invest.application.eval.EvalRunInProgressException;
import com.portfolio.invest.domain.eval.EvalErrorCode;
import com.portfolio.invest.domain.eval.EvalException;
import com.portfolio.invest.domain.eval.EvalRunRow;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.infrastructure.security.ActiveUserStatusCache;
import com.portfolio.invest.infrastructure.security.SecurityConfig;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * eval admin 触发/历史/baseline/补注端点切片（MS-30 B5，设计规格 §2.5/§7.1）：端点绑定 +
 * 安全规则（匿名 401 / 非管理员 403 / ADMIN 放行）+ 异常→HTTP 映射（进行中 409
 * EVAL_RUN_IN_PROGRESS、基准不合格 422 ERR_BASELINE_INELIGIBLE、行缺失 404）。引入真实
 * SecurityConfig（/api/admin/** 路径级 hasRole，无需注解），沿 UserAdminControllerSliceTest 形态。
 */
@WebMvcTest(EvalTriggerController.class)
@Import({SecurityConfig.class, ActiveUserStatusCache.class})
class EvalTriggerControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private EvalAdminApplicationService service;

    // SecurityConfig 装配所需依赖（切片内无真实实现）
    @MockitoBean
    private UserRepository userRepository;
    @MockitoBean
    private UserDetailsService userDetailsService;
    @MockitoBean
    private RememberMeServices rememberMeServices;
    @MockitoBean
    private PersistentTokenRepository persistentTokenRepository;

    private EvalRunRow runRow(long id, String status, String alertStatus, boolean baseline) {
        return new EvalRunRow(id, "SCHEDULED", status,
                Instant.parse("2026-10-10T02:17:00Z"), Instant.parse("2026-10-10T03:25:00Z"),
                18, 2, 0, Map.of("MARKET_FACT", new int[]{10, 0, 0}),
                Map.of("system.invest", 3), "qb-hash", alertStatus, baseline, false,
                List.of(), 4_080_000L, "/data/eval-report-" + id + ".json");
    }

    @DisplayName("匿名触发评测返回401")
    @Test
    void givenAnonymous_whenTriggerEval_then401() throws Exception {
        mvc.perform(post("/api/admin/eval/run"))
                .andExpect(status().isUnauthorized());
    }

    @DisplayName("非管理员触发评测返回403")
    @Test
    @WithMockUser(roles = "USER")
    void givenNonAdmin_whenTriggerEval_then403() throws Exception {
        mvc.perform(post("/api/admin/eval/run"))
                .andExpect(status().isForbidden());
    }

    @DisplayName("管理员触发评测返回202与runId且记录MANUAL触发")
    @Test
    @WithMockUser(roles = "ADMIN")
    void givenAdminAndIdleScheduler_whenTriggerEval_then202WithRunId() throws Exception {
        when(service.triggerManual()).thenReturn(7L);

        mvc.perform(post("/api/admin/eval/run"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value(7));
    }

    @DisplayName("评测进行中再触发返回409 EVAL_RUN_IN_PROGRESS")
    @Test
    @WithMockUser(roles = "ADMIN")
    void givenRunInProgress_whenTriggerEval_then409WithCode() throws Exception {
        when(service.triggerManual())
                .thenThrow(new EvalRunInProgressException("评测运行进行中（手动与定时互斥），请稍后再试"));

        mvc.perform(post("/api/admin/eval/run"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVAL_RUN_IN_PROGRESS"));
    }

    @DisplayName("管理员查询运行历史返回倒序列表含得分判定与baseline标记")
    @Test
    @WithMockUser(roles = "ADMIN")
    void givenAdmin_whenListRuns_thenHistoryWithVerdictFields() throws Exception {
        when(service.recentRuns(20)).thenReturn(List.of(
                runRow(7L, "COMPLETED", "NONE", true),
                runRow(6L, "RUNNING", "NONE", false)));

        mvc.perform(get("/api/admin/eval/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$[0].alertStatus").value("NONE"))
                .andExpect(jsonPath("$[0].baseline").value(true))
                .andExpect(jsonPath("$[0].totalPass").value(18))
                .andExpect(jsonPath("$[0].questionBankHash").value("qb-hash"))
                .andExpect(jsonPath("$[1].id").value(6))
                .andExpect(jsonPath("$[1].status").value("RUNNING"));

        verify(service).recentRuns(20);
    }

    @DisplayName("limit参数透传查询")
    @Test
    @WithMockUser(roles = "ADMIN")
    void givenLimitParam_whenListRuns_thenLimitPassedThrough() throws Exception {
        when(service.recentRuns(5)).thenReturn(List.of());

        mvc.perform(get("/api/admin/eval/runs").param("limit", "5"))
                .andExpect(status().isOk());

        verify(service).recentRuns(5);
    }

    @DisplayName("limit越界返回400")
    @Test
    @WithMockUser(roles = "ADMIN")
    void givenZeroLimit_whenListRuns_then400() throws Exception {
        mvc.perform(get("/api/admin/eval/runs").param("limit", "0"))
                .andExpect(status().isBadRequest());
    }

    @DisplayName("基准不合格的运行置位返回422 ERR_BASELINE_INELIGIBLE且不落库")
    @Test
    @WithMockUser(roles = "ADMIN")
    void givenIneligibleRun_whenPutBaseline_then422ErrBaselineIneligible() throws Exception {
        doThrow(new EvalException(EvalErrorCode.ERR_BASELINE_INELIGIBLE,
                "仅 COMPLETED 且非 DEGRADED 跑可置为基准（当前 status=PARTIAL, alert_status=NONE）"))
                .when(service).setBaseline(anyLong(), anyBoolean());

        mvc.perform(put("/api/admin/eval/runs/5/baseline")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseline\":true}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ERR_BASELINE_INELIGIBLE"));
    }

    @DisplayName("合格运行置位返回204")
    @Test
    @WithMockUser(roles = "ADMIN")
    void givenEligibleRun_whenPutBaseline_then204() throws Exception {
        mvc.perform(put("/api/admin/eval/runs/5/baseline")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseline\":true}"))
                .andExpect(status().isNoContent());

        verify(service).setBaseline(5L, true);
    }

    @DisplayName("置false清除基准返回204且透传")
    @Test
    @WithMockUser(roles = "ADMIN")
    void givenBaselineRun_whenPutBaselineFalse_then204WithFalse() throws Exception {
        mvc.perform(put("/api/admin/eval/runs/5/baseline")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseline\":false}"))
                .andExpect(status().isNoContent());

        verify(service).setBaseline(5L, false);
    }

    @DisplayName("运行不存在置位返回404")
    @Test
    @WithMockUser(roles = "ADMIN")
    void givenMissingRun_whenPutBaseline_then404() throws Exception {
        doThrow(new EvalException(EvalErrorCode.EVAL_RUN_NOT_FOUND, "eval 运行不存在: 99"))
                .when(service).setBaseline(anyLong(), anyBoolean());

        mvc.perform(put("/api/admin/eval/runs/99/baseline")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseline\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVAL_RUN_NOT_FOUND"));
    }

    @DisplayName("baseline请求体缺字段返回400")
    @Test
    @WithMockUser(roles = "ADMIN")
    void givenBodyWithoutBaseline_whenPutBaseline_then400() throws Exception {
        mvc.perform(put("/api/admin/eval/runs/5/baseline")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verify(service, never()).setBaseline(anyLong(), anyBoolean());
    }

    @DisplayName("管理员补注版本说明返回204且note透传")
    @Test
    @WithMockUser(roles = "ADMIN")
    void givenAdmin_whenPutAssetNote_then204AndNotePassed() throws Exception {
        mvc.perform(put("/api/admin/prompt-assets/9/note")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"调整引语风格\"}"))
                .andExpect(status().isNoContent());

        verify(service).updateAssetNote(9L, "调整引语风格");
    }

    @DisplayName("版本行不存在补注返回404")
    @Test
    @WithMockUser(roles = "ADMIN")
    void givenMissingAsset_whenPutAssetNote_then404() throws Exception {
        doThrow(new EvalException(EvalErrorCode.PROMPT_ASSET_NOT_FOUND, "提示词资产版本不存在: 99"))
                .when(service).updateAssetNote(anyLong(), anyString());

        mvc.perform(put("/api/admin/prompt-assets/99/note")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROMPT_ASSET_NOT_FOUND"));
    }

    @DisplayName("补注空note返回400")
    @Test
    @WithMockUser(roles = "ADMIN")
    void givenBlankNote_whenPutAssetNote_then400() throws Exception {
        mvc.perform(put("/api/admin/prompt-assets/9/note")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"\"}"))
                .andExpect(status().isBadRequest());

        verify(service, never()).updateAssetNote(anyLong(), anyString());
    }
}
