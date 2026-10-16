package com.portfolio.invest.bdd.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.application.eval.EvalHarvester;
import com.portfolio.invest.application.eval.EvalScheduler;
import com.portfolio.invest.bdd.CucumberSpringConfig;
import com.portfolio.invest.domain.eval.EvalRunRepository;
import com.portfolio.invest.domain.eval.EvalRunRow;
import io.cucumber.java.zh_cn.当;
import io.cucumber.java.zh_cn.那么;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Spliterators;
import java.util.stream.StreamSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 评测运行生命周期步骤（2026-10-15 审查 §三.6，eval 域首个 BDD 场景；P5-B3 补「失败收割」
 * 「DEGRADED 告警」两场景）：手动触发（HTTP 202 → EvalTriggerController → 门面 →
 * EvalScheduler）→ RUNNING 行可见（GET /runs 轮询口径）→ 子进程退出报告产出 →
 * EvalHarvester 收割落终态 → 历史可见。
 *
 * <p>子进程替身：{@code EvalScheduler} 以 {@code @MockitoBean} 整体打桩（triggerNow
 * 真插 RUNNING 行返 runId，不真起 JVM）——互斥/CAS/env 白名单/超时看护归
 * {@code EvalSchedulerTest} 钉死，真子进程全链归 opt-in 的
 * {@code EvalSchedulerEndToEndTest}（真跑 8~10 分钟，不进常规 suite）。收割器全链真实：
 * 真库（Testcontainers PG）+ 真报告文件（schema=eval-agent-report/2）+ 真回归判定
 * （无基准 → NO_BASELINE，理由留痕；可比基准 + 降幅超阈 → DEGRADED 红卡留痕）+ 真归档
 * （per-run 副本落盘）。DEGRADED 场景经 PUT /baseline 真置位/取消（收尾还原，防跨场景
 * 「无 baseline」断言漂移）；告警通道飞书未配置时优雅返回 false、邮件降级无收件人空转，
 * 全程不出网——留痕断言以库表为准（NFR-7）。
 */
public class EvalRunSteps {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    EvalScheduler evalScheduler;

    @Autowired
    EvalHarvester evalHarvester;

    @Autowired
    EvalRunRepository runRepository;

    @Autowired
    ScenarioContext ctx;

    private final ObjectMapper mapper = new ObjectMapper();

    /** 本场景产出的报告原件（收割后归档为同目录 per-run 副本）。 */
    private Path reportPath;

    /** 子进程退出码（收割入参；默认 0——「崩溃无报告」步骤置非零）。 */
    private int exitCode;

    /** 本场景提升为 baseline 的运行 id（场景收尾取消置位，防跨场景「无 baseline」断言漂移）。 */
    private Long baselineRunId;

    /**
     * 场景级状态重置：glue 类为 Spring 单例，多场景共享同一实例——P5 起同 feature 多场景
     * （场景 2 置 exitCode/不存在的 reportPath）曾串扰场景 1/3 的收割。每场景前清零，
     * 与 {@link MockResetHooks} 的 mock 重置同口径。
     */
    @io.cucumber.java.Before
    public void resetScenarioState() {
        reportPath = null;
        exitCode = 0;
        baselineRunId = null;
    }

    @当("管理员手动触发评测")
    public void 手动触发评测() throws Exception {
        // 子进程替身：真插 RUNNING 行（真仓库、真轨道/触发方列）返 runId——不起真进程
        when(evalScheduler.triggerNow("MANUAL")).thenAnswer(invocation ->
                runRepository.insertRunning("MANUAL", EvalRunRow.TRACK_AGENT, "bdd-eval-report.json"));
        MvcResult result = mockMvc.perform(post("/api/admin/eval/run").session(adminSession()))
                .andReturn();
        ctx.setLastResponse(result);
    }

    @当("管理员再次手动触发评测")
    public void 再次手动触发评测() throws Exception {
        手动触发评测(); // 同替身口径：第二插 RUNNING 行返新 runId（stub 场景内复用，MockResetHooks 场景间清零）
    }

    @当("管理员将该评测运行置为 baseline")
    public void 置为baseline() throws Exception {
        baselineRunId = ctx.getEvalRunId();
        MvcResult result = mockMvc.perform(put("/api/admin/eval/runs/{id}/baseline", baselineRunId)
                        .session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseline\":true}"))
                .andReturn();
        ctx.setLastResponse(result);
    }

    @当("管理员将该评测运行取消 baseline 标记")
    public void 取消baseline() throws Exception {
        MvcResult result = mockMvc.perform(put("/api/admin/eval/runs/{id}/baseline", baselineRunId)
                        .session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseline\":false}"))
                .andReturn();
        ctx.setLastResponse(result);
    }

    @那么("baseline 置位受理返回 {int}")
    public void baseline置位受理(int httpStatus) throws Exception {
        status().is(httpStatus).match(ctx.getLastResponse());
    }

    @那么("触发受理返回 {int} 且 runId 为正")
    public void 触发受理(int httpStatus) throws Exception {
        status().is(httpStatus).match(ctx.getLastResponse());
        JsonNode body = mapper.readTree(ctx.getLastResponse().getResponse().getContentAsString());
        assertThat(body.path("runId").asLong()).isPositive();
        ctx.setEvalRunId(body.path("runId").asLong());
    }

    @那么("该运行状态为 {string} 且触发方为 {string}")
    public void 运行状态与触发方(String status, String triggeredBy) throws Exception {
        assertThat(runOfMine().path("status").asText()).isEqualTo(status);
        assertThat(runOfMine().path("triggeredBy").asText()).isEqualTo(triggeredBy);
    }

    @那么("该运行状态为 {string}")
    public void 运行状态(String status) throws Exception {
        assertThat(runOfMine().path("status").asText()).isEqualTo(status);
    }

    @那么("该运行状态为 {string} 且通过数为 {int}")
    public void 运行状态与通过数(String status, int totalPass) throws Exception {
        assertThat(runOfMine().path("status").asText()).isEqualTo(status);
        assertThat(runOfMine().path("totalPass").asInt()).isEqualTo(totalPass);
    }

    @那么("该运行错误留痕含 {string}")
    public void 错误留痕含(String fragment) {
        EvalRunRow row = runRepository.findById(ctx.getEvalRunId()).orElseThrow();
        assertThat(row.verdictReasons()).anyMatch(reason -> reason.contains(fragment));
    }

    @那么("该运行告警状态为 {string} 且判定留痕含 {string}")
    public void 告警状态与判定留痕(String alertStatus, String fragment) {
        EvalRunRow row = runRepository.findById(ctx.getEvalRunId()).orElseThrow();
        assertThat(row.alertStatus()).isEqualTo(alertStatus);
        assertThat(row.verdictReasons()).anyMatch(reason -> reason.contains(fragment));
    }

    @当("该运行的评测子进程退出，报告 pass {int} fail {int} error {int}")
    public void 产出报告(int pass, int fail, int error) throws IOException {
        写报告(pass, fail, error, null);
    }

    @当("该运行的评测子进程退出，报告 pass {int} fail {int} error {int}，题库哈希 {string}")
    public void 产出报告含题库哈希(int pass, int fail, int error, String questionBankHash) throws IOException {
        // 回归判定可比性指纹之一：双侧题库 hash 齐备且相等才可判相对降幅（EvalRegressionJudge）
        写报告(pass, fail, error, questionBankHash);
    }

    @当("该运行的评测子进程以退出码 {int} 崩溃且无报告产出")
    public void 崩溃无报告(int code) {
        // 子进程非零退出且未写报告：收割读不到报告 → markFailed 口径（报告在但非零退 → PARTIAL）
        exitCode = code;
        reportPath = Path.of("bdd-eval-nonexistent-report-" + ctx.getEvalRunId() + ".json");
    }

    private void 写报告(int pass, int fail, int error, String questionBankHash) throws IOException {
        // 题级明细与 summary 计数严格一致（judge 的 byCategory/outcomes 从 questions 直读）
        List<String> statuses = new ArrayList<>();
        for (int i = 0; i < pass; i++) {
            statuses.add("PASS");
        }
        for (int i = 0; i < fail; i++) {
            statuses.add("FAIL");
        }
        for (int i = 0; i < error; i++) {
            statuses.add("ERROR");
        }
        StringBuilder questions = new StringBuilder();
        for (int i = 0; i < statuses.size(); i++) {
            questions.append("{\"id\":\"bdd-q").append(i + 1)
                    .append("\",\"category\":\"投研问答\",\"status\":\"").append(statuses.get(i)).append("\"}");
            if (i + 1 < statuses.size()) {
                questions.append(',');
            }
        }
        String questionBankHashEntry = questionBankHash == null ? "" :
                "\"questionBankHash\":\"" + questionBankHash + "\",";
        reportPath = Files.createTempFile("bdd-eval-report-" + ctx.getEvalRunId(), ".json");
        Files.writeString(reportPath, """
                {"schema":"eval-agent-report/2",
                 "summary":{"pass":%d,"fail":%d,"error":%d,"total":%d},
                 "questions":[%s],
                 "runMeta":{%s"totalDurationMs":1500}}
                """.formatted(pass, fail, error, pass + fail + error, questions, questionBankHashEntry));
    }

    @当("收割该评测运行")
    public void 收割() {
        // 调度看护链路的收割调用点（awaitAndHarvestTrack → harvester.harvest）；
        // 非超时：exitCode=0 正常收尾，非零（崩溃步骤置位）→ 报告缺失 FAILED / 报告在 PARTIAL
        evalHarvester.harvest(ctx.getEvalRunId(), reportPath, exitCode, false);
    }

    @那么("该运行判定留痕含 {string}")
    public void 判定留痕含(String fragment) {
        EvalRunRow row = runRepository.findById(ctx.getEvalRunId()).orElseThrow();
        assertThat(row.verdictReasons()).anyMatch(reason -> reason.contains(fragment));
        assertThat(row.alertStatus()).isEqualTo(EvalRunRow.ALERT_NONE); // NO_BASELINE 收敛落 NONE
    }

    @那么("该运行报告已归档为 per-run 副本")
    public void 报告已归档() {
        EvalRunRow row = runRepository.findById(ctx.getEvalRunId()).orElseThrow();
        assertThat(row.reportPath()).contains("eval-report-" + ctx.getEvalRunId() + ".json");
        assertThat(Files.isRegularFile(Path.of(row.reportPath()))).isTrue();
    }

    /** 运行历史（GET /runs，admin 会话）中定位本场景 runId 的行——不假设本行最新（防残留行干扰）。 */
    private JsonNode runOfMine() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/eval/runs").param("limit", "20")
                        .session(adminSession()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rows = mapper.readTree(result.getResponse().getContentAsString());
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(rows.elements(), 0), false)
                .filter(row -> row.path("id").asLong() == ctx.getEvalRunId())
                .findFirst()
                .orElseThrow(() -> new AssertionError("运行历史未见本场景 runId=" + ctx.getEvalRunId()));
    }

    /** 内置管理员（AdminSeedRunner 种子）登录后台，会话在场景内复用（同 McpTokenSteps）。 */
    private MockHttpSession adminSession() throws Exception {
        if (ctx.getAdminSession() == null) {
            MvcResult result = mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + CucumberSpringConfig.ADMIN_USERNAME
                                    + "\",\"password\":\"" + CucumberSpringConfig.ADMIN_PASSWORD + "\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
            ctx.setAdminSession((MockHttpSession) result.getRequest().getSession(false));
        }
        return ctx.getAdminSession();
    }
}
