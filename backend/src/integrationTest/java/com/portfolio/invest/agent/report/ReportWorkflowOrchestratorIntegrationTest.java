package com.portfolio.invest.agent.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.portfolio.invest.agent.trust.ToolInvocation;
import com.portfolio.invest.agent.trust.TrustContext;
import com.portfolio.invest.domain.research.ReportAnchor;
import com.portfolio.invest.domain.research.ReportAnchorRepository;
import com.portfolio.invest.domain.research.ReportAssumption;
import com.portfolio.invest.domain.research.ReportAssumptionRepository;
import com.portfolio.invest.domain.research.ReportTask;
import com.portfolio.invest.domain.research.ReportTaskRepository;
import com.portfolio.invest.domain.research.ResearchProject;
import com.portfolio.invest.domain.research.ResearchProjectRepository;
import com.portfolio.invest.domain.research.ResearchReport;
import com.portfolio.invest.domain.research.ResearchReportRepository;
import com.portfolio.invest.domain.research.ResearchStage;
import com.portfolio.invest.support.PostgresTestSupport;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.model.ChatUsage;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import reactor.core.publisher.Flux;

/**
 * 研报工作流编排器集成（MS-32 B9，真 PG）：@TestBean 以 fake runner 工厂整体替换真实现
 * （沿 HarnessAgentStateIsolationIntegrationTest 先例）；sink 为 B11 真落库实现
 * （ReportApplicationService，原 B9 期 fake sink 装配缺口已由 B11 收口）。happy path 全链
 * 断言：任务行终态 SUCCEEDED + tokenUsage&gt;0（真 ReportUsageMiddleware 经事件源充电）+
 * 研报/锚点/假设真实落库（PENDING_CONFIRM + JSONB args + anchorIndex 绑定）+ 配额 settle 行
 * + 终态 state 会话清理（Ruling-5）。
 *
 * <p>异步执行：submit 走 reportTaskExecutor 线程池（core=max=2），主线程轮询研报表
 * （sink 同事务提交研报+结算——行可见即全链完成）后断言。
 */
@SpringBootTest(properties = {
        "invest.mcp.harness.state-root=build/report-workflow-it/state"})
class ReportWorkflowOrchestratorIntegrationTest extends PostgresTestSupport {

    private static final Path STATE_ROOT = Path.of("build/report-workflow-it/state");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String QUOTE_TRUTH = "现价986.50元，总市值8500亿";
    private static final String VALUATION_TRUTH = "市盈率35.40，市净率9.80";
    private static final String FINANCIALS_TRUTH = "净资产收益率31.20，毛利率91.50";
    private static final String VALID_CONTENT_MD =
            "现价986.50元，总市值8500亿，市盈率35.40，市净率9.80。\n\n## 关键假设\n1. 现价986.50元处于合理区间\n2. 市盈率35.40具备安全边际\n";

    /** bean 名按字段名替换 ReportTaskExecutorConfig#reportAgentRunnerFactory（零真 LLM）。 */
    @TestBean(methodName = "fakeRunnerFactory")
    ReportAgentRunnerFactory reportAgentRunnerFactory;

    @Autowired
    private ReportWorkflowOrchestrator orchestrator;

    @Autowired
    private ReportTaskRepository taskRepository;

    @Autowired
    private ResearchProjectRepository projectRepository;

    @Autowired
    private ResearchReportRepository reportRepository;

    @Autowired
    private ReportAnchorRepository anchorRepository;

    @Autowired
    private ReportAssumptionRepository assumptionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    static ReportAgentRunnerFactory fakeRunnerFactory() {
        return (userId, usageMiddleware) -> new ReportAgentRunner() {
            private int calls;

            @Override
            public String call(RuntimeContext rc, String userPrompt) {
                // Ruling-21 修复③拓扑拟真（与单测 ScriptedRunner 同款）：派生新 rc 模拟
                // HarnessAgent.ensureSessionDefaults，真值记录进派生 rc，轮末代拟 harness
                // 接线（onAgent 完成流）——编排器只经中间件 drain 通道拿真值
                RuntimeContext derived = RuntimeContext.builder(rc).build();
                String result;
                switch (++calls) {
                    case 1:
                        seedPool(derived, truth("get_quote", QUOTE_TRUTH));
                        charge(usageMiddleware, 1200L);
                        result = "收集小结：get_quote 已取数";
                        break;
                    case 2:
                        seedPool(derived, truth("get_valuation", VALUATION_TRUTH));
                        charge(usageMiddleware, 800L);
                        result = "分析小结：估值分位中等";
                        break;
                    default:
                        seedPool(derived, truth("get_financials", FINANCIALS_TRUTH));
                        charge(usageMiddleware, 500L);
                        result = fencedReport("贵州茅台深度研报（集成）", VALID_CONTENT_MD, "无特别局限");
                }
                usageMiddleware.onAgent(null, derived, new AgentInput(List.of()),
                        input -> Flux.just((AgentEvent) new AgentResultEvent(Msg.builder()
                                .id("round-" + calls).role(MsgRole.ASSISTANT).textContent("轮收尾").build())))
                        .blockLast();
                return result;
            }
        };
    }

    @DisplayName("happy path 全链：fake runner 驱动 submit → 任务 SUCCEEDED + 真落库研报/锚点/假设/结算 + state 清理")
    @Test
    void givenFakedRunner_whenSubmit_thenTaskSucceededAndSinkPersistsRealRows()
            throws Exception {
        Long userId = jdbcTemplate.queryForObject(
                "INSERT INTO app_user(username, password_hash, role, status) "
                        + "VALUES (?, 'x', 'USER', 'APPROVED') RETURNING id",
                Long.class, "report_wf_" + System.nanoTime());
        ResearchProject project = projectRepository.save(ResearchProject.create(
                userId, "600519", "贵州茅台", null, "茅台深度研究", ResearchStage.NEW_ANALYSIS, Instant.now()));
        ReportTask task = taskRepository.save(ReportTask.create(userId, project.id(), Instant.now()));
        seedStateFile(userId, task.id());

        orchestrator.submit(task.id(), userId, project.id());

        // sink 同事务提交研报+结算——轮询研报行可见即全链完成（任务行先行 SUCCEEDED，上限 10s）
        ResearchReport report = null;
        for (int i = 0; i < 100 && report == null; i++) {
            Thread.sleep(100);
            report = reportRepository.findByTaskId(task.id()).orElse(null);
        }
        assertThat(report).as("真 sink 落库：研报行 10s 内可见").isNotNull();

        ReportTask row = taskRepository.findById(task.id()).orElseThrow();

        assertThat(row.status()).isEqualTo(ReportTask.Status.SUCCEEDED);
        assertThat(row.stage()).isEqualTo(ReportTask.Stage.VALIDATING);
        assertThat(row.tokenUsage()).as("三轮充电 1200+800+500").isEqualTo(2500L);
        assertThat(row.startedAt()).isNotNull();
        assertThat(row.finishedAt()).isNotNull();
        assertThat(row.errorCode()).isNull();

        assertThat(report.status()).isEqualTo(ResearchReport.Status.PENDING_CONFIRM);
        assertThat(report.title()).isEqualTo("贵州茅台深度研报（集成）");
        assertThat(report.contentMd()).isEqualTo(VALID_CONTENT_MD);
        assertThat(report.limitations()).isEqualTo("无特别局限");
        List<ReportAnchor> anchors = anchorRepository.findByReportId(report.id());
        assertThat(anchors).isNotEmpty();
        assertThat(anchors).allMatch(anchor -> "VERIFIED".equals(anchor.state()));
        List<ReportAssumption> assumptions = assumptionRepository.findByReportId(report.id());
        assertThat(assumptions).hasSize(2);
        assertThat(assumptions).allSatisfy(assumption -> assertThat(assumption.anchorId()).isNotNull());
        Long settled = jdbcTemplate.queryForObject(
                "SELECT tokens_used FROM report_token_quota WHERE user_id = ?", Long.class, userId);
        assertThat(settled).as("sink 结算与任务行 tokenUsage 一致").isEqualTo(2500L);
        assertThat(STATE_ROOT.resolve(String.valueOf(userId)).resolve("report-" + task.id()))
                .as("终态后 state 会话目录已清理（Ruling-5）").doesNotExist();
    }

    // ———— 脚手架（与单测同款 fixture，跨源集不可共享则就地复制） ————

    private static void seedStateFile(Long userId, Long taskId) throws IOException {
        Path sessionDir = STATE_ROOT.resolve(String.valueOf(userId)).resolve("report-" + taskId);
        Files.createDirectories(sessionDir);
        Files.writeString(sessionDir.resolve("agent_state.json"), "{\"messages\":[]}");
    }

    private static ToolInvocation truth(String toolName, String resultText) {
        return new ToolInvocation(toolName, Map.of("code", "600519"), resultText, List.of(),
                "2026-10-11 09:00:00", ToolInvocation.AsOfKind.DATA, false, false, 120L);
    }

    private static void seedPool(RuntimeContext rc, ToolInvocation... invocations) {
        try {
            Method fresh = TrustContext.class.getDeclaredMethod("fresh", RuntimeContext.class);
            fresh.setAccessible(true);
            fresh.invoke(null, rc);
            Method record = TrustContext.class.getDeclaredMethod("record", ToolInvocation.class);
            record.setAccessible(true);
            Object pool = TrustContext.current(rc);
            for (ToolInvocation invocation : invocations) {
                record.invoke(pool, invocation);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("真值池反射注入失败", e);
        }
    }

    private static void charge(ReportUsageMiddleware middleware, long tokens) {
        middleware.onAgent(null, RuntimeContext.builder().build(), new AgentInput(List.of()),
                input -> Flux.just((AgentEvent) new ModelCallEndEvent("charge",
                        new ChatUsage((int) tokens, 0, 0.01)))).blockLast();
    }

    private static String fencedReport(String title, String contentMd, String limitations) {
        ObjectNode node = JSON.createObjectNode();
        node.put("specVersion", 1).put("title", title)
                .put("contentMd", contentMd).put("limitations", limitations);
        return "```research-report\n" + node + "\n```";
    }
}
