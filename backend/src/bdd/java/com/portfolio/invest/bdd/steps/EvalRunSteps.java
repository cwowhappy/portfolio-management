package com.portfolio.invest.bdd.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * 评测运行生命周期步骤（2026-10-15 审查 §三.6，eval 域首个 BDD 场景）：手动触发
 * （HTTP 202 → EvalTriggerController → 门面 → EvalScheduler）→ RUNNING 行可见
 * （GET /runs 轮询口径）→ 子进程退出报告产出 → EvalHarvester 收割落终态 → 历史可见。
 *
 * <p>子进程替身：{@code EvalScheduler} 以 {@code @MockitoBean} 整体打桩（triggerNow
 * 真插 RUNNING 行返 runId，不真起 JVM）——互斥/CAS/env 白名单/超时看护归
 * {@code EvalSchedulerTest} 钉死，真子进程全链归 opt-in 的
 * {@code EvalSchedulerEndToEndTest}（真跑 8~10 分钟，不进常规 suite）。收割器全链真实：
 * 真库（Testcontainers PG）+ 真报告文件（schema=eval-agent-report/2）+ 真回归判定
 * （无基准 → NO_BASELINE，理由留痕）+ 真归档（per-run 副本落盘）。
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

    @当("管理员手动触发评测")
    public void 手动触发评测() throws Exception {
        // 子进程替身：真插 RUNNING 行（真仓库、真轨道/触发方列）返 runId——不起真进程
        when(evalScheduler.triggerNow("MANUAL")).thenAnswer(invocation ->
                runRepository.insertRunning("MANUAL", EvalRunRow.TRACK_AGENT, "bdd-eval-report.json"));
        MvcResult result = mockMvc.perform(post("/api/admin/eval/run").session(adminSession()))
                .andReturn();
        ctx.setLastResponse(result);
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

    @那么("该运行状态为 {string} 且通过数为 {int}")
    public void 运行状态与通过数(String status, int totalPass) throws Exception {
        assertThat(runOfMine().path("status").asText()).isEqualTo(status);
        assertThat(runOfMine().path("totalPass").asInt()).isEqualTo(totalPass);
    }

    @当("该运行的评测子进程退出，报告 pass {int} fail {int} error {int}")
    public void 产出报告(int pass, int fail, int error) throws IOException {
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
        reportPath = Files.createTempFile("bdd-eval-report-" + ctx.getEvalRunId(), ".json");
        Files.writeString(reportPath, """
                {"schema":"eval-agent-report/2",
                 "summary":{"pass":%d,"fail":%d,"error":%d,"total":%d},
                 "questions":[%s],
                 "runMeta":{"totalDurationMs":1500}}
                """.formatted(pass, fail, error, pass + fail + error, questions));
    }

    @当("收割该评测运行")
    public void 收割() {
        // 调度看护链路的收割调用点（awaitAndHarvestTrack → harvester.harvest）；
        // exitCode=0 非超时：正常收尾口径，状态 COMPLETED
        evalHarvester.harvest(ctx.getEvalRunId(), reportPath, 0, false);
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
