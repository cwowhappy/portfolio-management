package com.portfolio.invest.agent.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.portfolio.invest.application.research.ReportTaskSink;
import com.portfolio.invest.agent.trust.ToolInvocation;
import com.portfolio.invest.agent.trust.TrustContext;
import com.portfolio.invest.domain.research.ReportTask;
import com.portfolio.invest.domain.research.ReportTaskRepository;
import com.portfolio.invest.domain.research.ResearchProject;
import com.portfolio.invest.domain.research.ResearchProjectRepository;
import com.portfolio.invest.domain.research.ResearchStage;
import com.portfolio.invest.support.PostgresTestSupport;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatUsage;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;

/**
 * 研报工作流编排器集成（MS-32 B9，真 PG）：@TestBean 以 fake runner 工厂整体替换真实现
 * （沿 HarnessAgentStateIsolationIntegrationTest 先例），@TestConfiguration 供 fake sink
 * （B11 未落地前的装配缺口，Ruling：Spring 装配完整性由 B11 收口）；happy path 全链断言：
 * 任务行终态 SUCCEEDED + tokenUsage&gt;0（真 ReportUsageHook 经事件源充电）+ sink 回调参数
 * （anchors/assumptions）+ 终态 state 会话清理（Ruling-5）。研报表落库归 B11 集成测试。
 *
 * <p>异步执行：submit 走 reportTaskExecutor 线程池（core=max=2），fake sink 回调闭锁汇流，
 * 主线程 await 后断言（任务行读取新事务可见——异步线程已 saveAndFlush 提交）。
 */
@SpringBootTest(properties = {
        "invest.mcp.harness.state-root=build/report-workflow-it/state"})
class ReportWorkflowOrchestratorIntegrationTest extends PostgresTestSupport {

    private static final Path STATE_ROOT = Path.of("build/report-workflow-it/state");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Agent STUB_AGENT = mock(Agent.class);

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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RecordingSink sink;

    static ReportAgentRunnerFactory fakeRunnerFactory() {
        return (userId, usageHook) -> new ReportAgentRunner() {
            private int calls;

            @Override
            public String call(RuntimeContext rc, String userPrompt) {
                switch (++calls) {
                    case 1:
                        seedPool(rc, truth("get_quote", QUOTE_TRUTH));
                        charge(usageHook, 1200L);
                        return "收集小结：get_quote 已取数";
                    case 2:
                        seedPool(rc, truth("get_valuation", VALUATION_TRUTH));
                        charge(usageHook, 800L);
                        return "分析小结：估值分位中等";
                    default:
                        seedPool(rc, truth("get_financials", FINANCIALS_TRUTH));
                        charge(usageHook, 500L);
                        return fencedReport("贵州茅台深度研报（集成）", VALID_CONTENT_MD, "无特别局限");
                }
            }
        };
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SinkConfig {

        @Bean
        RecordingSink reportTaskSink() {
            return new RecordingSink();
        }
    }

    @DisplayName("happy path 全链：fake runner 驱动 submit → 任务行 SUCCEEDED/tokenUsage>0 + sink 回调 + state 清理")
    @Test
    void givenFakedRunnerAndSink_whenSubmit_thenTaskSucceededRowAndCallbackAndStateCleaned()
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

        assertThat(sink.succeededLatch.await(10, TimeUnit.SECONDS))
                .as("fake runner 即时回放，10s 内必达成功回调").isTrue();

        ReportTask row = taskRepository.findById(task.id()).orElseThrow();
        assertThat(row.status()).isEqualTo(ReportTask.Status.SUCCEEDED);
        assertThat(row.stage()).isEqualTo(ReportTask.Stage.VALIDATING);
        assertThat(row.tokenUsage()).as("三轮充电 1200+800+500").isEqualTo(2500L);
        assertThat(row.startedAt()).isNotNull();
        assertThat(row.finishedAt()).isNotNull();
        assertThat(row.errorCode()).isNull();

        assertThat(sink.succeeded).hasSize(1);
        ReportTaskSink.SucceededPayload payload = sink.succeeded.get(0).payload();
        assertThat(payload.title()).isEqualTo("贵州茅台深度研报（集成）");
        assertThat(payload.contentMd()).isEqualTo(VALID_CONTENT_MD);
        assertThat(payload.anchors()).isNotEmpty();
        assertThat(payload.anchors()).allMatch(line -> "VERIFIED".equals(line.state()));
        assertThat(payload.assumptions()).hasSize(2);
        assertThat(payload.assumptions()).allMatch(line -> line.anchorIndex() >= 0);
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

    private static void charge(ReportUsageHook hook, long tokens) {
        Msg msg = Msg.builder().id("charge").role(MsgRole.ASSISTANT).textContent("轮")
                .usage(new ChatUsage((int) tokens, 0, 0.0)).build();
        hook.onEvent(new PostReasoningEvent(STUB_AGENT, "deepseek-chat", null, msg)).block();
    }

    private static String fencedReport(String title, String contentMd, String limitations) {
        ObjectNode node = JSON.createObjectNode();
        node.put("specVersion", 1).put("title", title)
                .put("contentMd", contentMd).put("limitations", limitations);
        return "```research-report\n" + node + "\n```";
    }

    /** fake sink：闭锁汇流异步回调，参数就地留存。 */
    static final class RecordingSink implements ReportTaskSink {
        final CountDownLatch succeededLatch = new CountDownLatch(1);
        final List<Callback> succeeded = new java.util.concurrent.CopyOnWriteArrayList<>();

        record Callback(Long taskId, long tokenUsage, SucceededPayload payload) {}

        @Override
        public void onTaskSucceeded(Long taskId, long tokenUsage, SucceededPayload payload) {
            succeeded.add(new Callback(taskId, tokenUsage, payload));
            succeededLatch.countDown();
        }

        @Override
        public void onTaskFailed(Long taskId, long tokenUsage, String errorCode, String errorMessage) {
            // happy path 集成用例不应触达；触发则闭锁不释放，用例以超时失败暴露
        }
    }
}
