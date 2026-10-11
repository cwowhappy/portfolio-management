package com.portfolio.invest.agent.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.portfolio.invest.application.research.ReportTaskSink;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.research.Falsifier;
import com.portfolio.invest.domain.research.ProjectStatus;
import com.portfolio.invest.domain.research.ReportTask;
import com.portfolio.invest.domain.research.ReportTaskRepository;
import com.portfolio.invest.domain.research.ResearchProject;
import com.portfolio.invest.domain.research.ResearchProjectRepository;
import com.portfolio.invest.domain.research.ResearchStage;
import com.portfolio.invest.domain.research.StageCompletionService;
import com.portfolio.invest.domain.research.StrategyDoc;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatUsage;
import com.portfolio.invest.agent.trust.ToolInvocation;
import com.portfolio.invest.agent.trust.TrustContext;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 研报工作流编排器单测（MS-32 B9，设计规格 §3.2）：fake runner 剧本驱动全状态机，零 LLM。
 * 七剧本：①happy ②错值草稿回炉 ③两连败 ④围栏缺失格式重试仍败 ⑤runner 异常 ⑥token 超限
 * ⑦真值池跨轮累积；另补三钉：④b 格式重试成功续跑 ⑧任务总超时（MutableClock 推进）
 * ⑨执行队列满拒绝（真单线程 ThreadPoolTaskExecutor 被占满）。
 *
 * <p>真值池注入走反射（{@code TrustContext.fresh/record} 包私有于 trust 包，B0 Ruling-1
 * 同款取舍——ReportProbeTest 因同包可直调，本类在 report 包须反射）；fake runner 每轮
 * call 首先调 fresh(rc) 模拟生产 TrustAgentHook PRE_CALL 清池时序（B0 探针 1-c 语义），
 * 再 record 本轮真值——编排器 call 返回后快照累积，跨轮无损依赖该时序。
 */
class ReportWorkflowOrchestratorTest {

    private static final Instant NOW = Instant.parse("2026-10-11T08:00:00Z");
    private static final Long USER_ID = 1L;
    private static final Long PROJECT_ID = 5L;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Agent STUB_AGENT = mock(Agent.class);

    /** 三轮各一条真值：行情/估值/财务（草稿四个数字全部可锚定）。 */
    private static final String QUOTE_TRUTH = "现价986.50元，总市值8500亿";
    private static final String VALUATION_TRUTH = "市盈率35.40，市净率9.80";
    private static final String FINANCIALS_TRUTH = "净资产收益率31.20，毛利率91.50";

    /** 过门正文：四个数字与真值精确一致；「## 关键假设」两条均可绑定。 */
    private static final String VALID_CONTENT_MD =
            "现价986.50元，总市值8500亿，市盈率35.40，市净率9.80。\n\n## 关键假设\n1. 现价986.50元处于合理区间\n2. 市盈率35.40具备安全边际\n";

    /** 错值正文：现值 1120.00 无真值支撑（超容差，不可归因）→ 未溯源比例越线回炉。 */
    private static final String WRONG_CONTENT_MD =
            "现价1120.00元，总市值8500亿，市盈率35.40，市净率9.80。\n\n## 关键假设\n1. 现价1120.00元处于合理区间\n2. 市盈率35.40具备安全边际\n";

    @TempDir
    Path tempDir;

    private final MutableClock clock = new MutableClock(NOW);

    @AfterEach
    void cleanFallback() {
        TrustContext.reset();
    }

    // ———— 剧本 1：happy path ————

    @DisplayName("剧本1：三轮正常+围栏JSON → SUCCEEDED；sink 收 outcome（anchors/assumptions）；state 文件清理")
    @Test
    void givenThreeRoundsAndFencedValidDraft_whenSubmit_thenSucceededWithOutcome() throws Exception {
        FakeTaskRepository tasks = new FakeTaskRepository();
        RecordingSink sink = new RecordingSink();
        ScriptedRunner runner = ScriptedRunner.of(
                rc -> { seedPool(rc, truth("get_quote", QUOTE_TRUTH)); return "收集小结：调用 get_quote"; },
                rc -> { seedPool(rc, truth("get_valuation", VALUATION_TRUTH)); return "分析小结：估值分位中等"; },
                rc -> fencedReport("贵州茅台深度研报", VALID_CONTENT_MD, "无特别局限"));
        ReportWorkflowOrchestrator orchestrator = orchestrator(runner, sink, tasks, props(), clock,
                directExecutor(), null);
        Long taskId = seedTask(tasks);
        seedStateFile(taskId);

        orchestrator.submit(taskId, USER_ID, PROJECT_ID);

        assertThat(runner.calls()).as("恰好三轮（收集/分析/草稿），无回炉").isEqualTo(3);
        assertThat(runner.prompts().get(0))
                .contains("600519").contains("贵州茅台").contains("茅台深度研究");
        ReportTask row = tasks.rows.get(taskId);
        assertThat(row.status()).isEqualTo(ReportTask.Status.SUCCEEDED);
        assertThat(row.stage()).isEqualTo(ReportTask.Stage.VALIDATING);
        assertThat(row.startedAt()).isNotNull();
        assertThat(row.finishedAt()).isNotNull();
        assertThat(sink.succeeded).hasSize(1);
        ReportTaskSink.SucceededPayload payload = sink.succeeded.get(0).payload();
        assertThat(payload.title()).isEqualTo("贵州茅台深度研报");
        assertThat(payload.contentMd()).isEqualTo(VALID_CONTENT_MD);
        assertThat(payload.assumptions()).hasSize(2);
        assertThat(payload.assumptions()).allMatch(line -> line.anchorIndex() >= 0);
        assertThat(payload.anchors()).isNotEmpty();
        assertThat(payload.anchors()).allMatch(line -> "VERIFIED".equals(line.state()));
        assertThat(stateDir(taskId)).as("终态后 report-* state 会话目录已清理").doesNotExist();
        assertThat(sink.failed).isEmpty();
    }

    // ———— 剧本 2：错值草稿 → 门 fail → 回炉轮修正 ————

    @DisplayName("剧本2：草稿错值→门fail→回炉轮给修正稿→SUCCEEDED（断言草稿+回炉两轮发生、反馈进回炉提示）")
    @Test
    void givenWrongDraftThenCorrectedRegen_whenSubmit_thenSucceededAfterRegen() {
        FakeTaskRepository tasks = new FakeTaskRepository();
        RecordingSink sink = new RecordingSink();
        ScriptedRunner runner = ScriptedRunner.of(
                rc -> { seedPool(rc, truth("get_quote", QUOTE_TRUTH)); return "收集小结"; },
                rc -> { seedPool(rc, truth("get_valuation", VALUATION_TRUTH)); return "分析小结"; },
                rc -> { seedPool(rc, truth("get_financials", FINANCIALS_TRUTH)); return fencedReport("初稿", WRONG_CONTENT_MD, "无"); },
                rc -> fencedReport("修正稿", VALID_CONTENT_MD, "无"));
        ReportWorkflowOrchestrator orchestrator = orchestrator(runner, sink, tasks, props(), clock,
                directExecutor(), null);
        Long taskId = seedTask(tasks);

        orchestrator.submit(taskId, USER_ID, PROJECT_ID);

        assertThat(runner.calls()).as("四轮：收集/分析/草稿/回炉").isEqualTo(4);
        assertThat(runner.prompts().get(3))
                .as("回炉轮提示携带门反馈（错值片段点名）")
                .contains("1120.00");
        assertThat(tasks.rows.get(taskId).status()).isEqualTo(ReportTask.Status.SUCCEEDED);
        assertThat(sink.succeeded).hasSize(1);
        assertThat(sink.succeeded.get(0).payload().title()).isEqualTo("修正稿");
    }

    // ———— 剧本 3：两连败 ————

    @DisplayName("剧本3：回炉轮仍错值 → FAILED(VALIDATION_FAILED)，反馈进 errorMessage")
    @Test
    void givenWrongDraftAndWrongRegen_whenSubmit_thenFailedValidationAfterRegenExhausted() {
        FakeTaskRepository tasks = new FakeTaskRepository();
        RecordingSink sink = new RecordingSink();
        ScriptedRunner runner = ScriptedRunner.of(
                rc -> { seedPool(rc, truth("get_quote", QUOTE_TRUTH)); return "收集小结"; },
                rc -> { seedPool(rc, truth("get_valuation", VALUATION_TRUTH)); return "分析小结"; },
                rc -> fencedReport("初稿", WRONG_CONTENT_MD, "无"),
                rc -> fencedReport("回炉稿", WRONG_CONTENT_MD, "无"));
        ReportWorkflowOrchestrator orchestrator = orchestrator(runner, sink, tasks, props(), clock,
                directExecutor(), null);
        Long taskId = seedTask(tasks);

        orchestrator.submit(taskId, USER_ID, PROJECT_ID);

        assertThat(runner.calls()).isEqualTo(4);
        ReportTask row = tasks.rows.get(taskId);
        assertThat(row.status()).isEqualTo(ReportTask.Status.FAILED);
        assertThat(row.errorCode()).isEqualTo(ReportWorkflowException.VALIDATION_FAILED);
        assertThat(row.errorMessage()).contains("无工具数据支撑");
        assertThat(sink.failed).hasSize(1);
        assertThat(sink.failed.get(0).errorCode()).isEqualTo(ReportWorkflowException.VALIDATION_FAILED);
        assertThat(sink.succeeded).isEmpty();
    }

    // ———— 剧本 4：围栏缺失 → 格式反馈重试 → 仍缺 ————

    @DisplayName("剧本4：草稿无围栏→格式重试一轮→仍无围栏→FAILED(PARSE_FAILED)")
    @Test
    void givenFenceMissingDraftAndStillMissingRetry_whenSubmit_thenFailedParse() {
        FakeTaskRepository tasks = new FakeTaskRepository();
        RecordingSink sink = new RecordingSink();
        ScriptedRunner runner = ScriptedRunner.of(
                rc -> { seedPool(rc, truth("get_quote", QUOTE_TRUTH)); return "收集小结"; },
                rc -> { seedPool(rc, truth("get_valuation", VALUATION_TRUTH)); return "分析小结"; },
                rc -> "研报全文 markdown：现价986.50元（未按契约给围栏）",
                rc -> "仍未给围栏的回复");
        ReportWorkflowOrchestrator orchestrator = orchestrator(runner, sink, tasks, props(), clock,
                directExecutor(), null);
        Long taskId = seedTask(tasks);

        orchestrator.submit(taskId, USER_ID, PROJECT_ID);

        assertThat(runner.calls()).as("四轮：收集/分析/草稿/格式重试").isEqualTo(4);
        assertThat(runner.prompts().get(3))
                .as("格式重试提示重申围栏输出契约")
                .contains("research-report");
        ReportTask row = tasks.rows.get(taskId);
        assertThat(row.status()).isEqualTo(ReportTask.Status.FAILED);
        assertThat(row.errorCode()).isEqualTo(ReportWorkflowException.PARSE_FAILED);
        assertThat(sink.failed).hasSize(1);
        assertThat(sink.failed.get(0).errorCode()).isEqualTo(ReportWorkflowException.PARSE_FAILED);
    }

    // ———— 剧本 4b（补钉）：围栏缺失 → 格式重试成功 ————

    @DisplayName("剧本4b：草稿无围栏→格式重试一轮给合规围栏→继续过门 SUCCEEDED")
    @Test
    void givenFenceMissingDraftButValidRetry_whenSubmit_thenSucceededAfterFormatRetry() {
        FakeTaskRepository tasks = new FakeTaskRepository();
        RecordingSink sink = new RecordingSink();
        ScriptedRunner runner = ScriptedRunner.of(
                rc -> { seedPool(rc, truth("get_quote", QUOTE_TRUTH)); return "收集小结"; },
                rc -> { seedPool(rc, truth("get_valuation", VALUATION_TRUTH)); return "分析小结"; },
                rc -> "研报全文 markdown（未按契约给围栏）",
                rc -> fencedReport("重拟稿", VALID_CONTENT_MD, "无特别局限"));
        ReportWorkflowOrchestrator orchestrator = orchestrator(runner, sink, tasks, props(), clock,
                directExecutor(), null);
        Long taskId = seedTask(tasks);

        orchestrator.submit(taskId, USER_ID, PROJECT_ID);

        assertThat(runner.calls()).isEqualTo(4);
        assertThat(runner.prompts().get(3)).contains("研报格式不合格");
        assertThat(tasks.rows.get(taskId).status()).isEqualTo(ReportTask.Status.SUCCEEDED);
        assertThat(sink.succeeded).hasSize(1);
        assertThat(sink.succeeded.get(0).payload().title()).isEqualTo("重拟稿");
    }

    // ———— 剧本 5：runner 异常 ————

    @DisplayName("剧本5：runner 抛异常 → FAILED(AGENT_ERROR)，异常消息入 errorMessage，state 清理仍执行")
    @Test
    void givenRunnerThrows_whenSubmit_thenFailedAgentError() throws Exception {
        FakeTaskRepository tasks = new FakeTaskRepository();
        RecordingSink sink = new RecordingSink();
        ScriptedRunner runner = ScriptedRunner.of(
                rc -> { throw new IllegalStateException("模型通道中断"); });
        ReportWorkflowOrchestrator orchestrator = orchestrator(runner, sink, tasks, props(), clock,
                directExecutor(), null);
        Long taskId = seedTask(tasks);
        seedStateFile(taskId);

        orchestrator.submit(taskId, USER_ID, PROJECT_ID);

        assertThat(runner.calls()).isEqualTo(1);
        ReportTask row = tasks.rows.get(taskId);
        assertThat(row.status()).isEqualTo(ReportTask.Status.FAILED);
        assertThat(row.errorCode()).isEqualTo(ReportWorkflowException.AGENT_ERROR);
        assertThat(row.errorMessage()).contains("模型通道中断");
        assertThat(sink.failed).hasSize(1);
        assertThat(stateDir(taskId)).as("失败终态同样清理 state").doesNotExist();
    }

    // ———— 剧本 6：单任务 token 上限 ————

    @DisplayName("剧本6：首轮即超单任务 token 上限 → 下一轮前 FAILED(QUOTA_TASK_CAP)，runner 只被调一次")
    @Test
    void givenUsageOverCapAfterFirstRound_whenSubmit_thenFailedQuotaTaskCap() {
        FakeTaskRepository tasks = new FakeTaskRepository();
        RecordingSink sink = new RecordingSink();
        AtomicReference<ReportUsageHook> capturedHook = new AtomicReference<>();
        ScriptedRunner runner = ScriptedRunner.of(
                rc -> {
                    seedPool(rc, truth("get_quote", QUOTE_TRUTH));
                    charge(capturedHook.get(), 150L);
                    return "收集小结";
                },
                rc -> "不应到达的分析轮");
        InvestProperties props = props();
        props.getReport().setQuota(new InvestProperties.Report.Quota(500_000L, 100L));
        ReportWorkflowOrchestrator orchestrator = orchestrator(runner, sink, tasks, props, clock,
                directExecutor(), capturedHook);
        Long taskId = seedTask(tasks);

        orchestrator.submit(taskId, USER_ID, PROJECT_ID);

        assertThat(runner.calls()).as("第二轮前被卡点拦截").isEqualTo(1);
        ReportTask row = tasks.rows.get(taskId);
        assertThat(row.status()).isEqualTo(ReportTask.Status.FAILED);
        assertThat(row.errorCode()).isEqualTo(ReportWorkflowException.QUOTA_TASK_CAP);
        assertThat(row.tokenUsage()).isEqualTo(150L);
        assertThat(sink.failed).hasSize(1);
        assertThat(sink.failed.get(0).tokenUsage()).isEqualTo(150L);
        assertThat(sink.failed.get(0).errorCode()).isEqualTo(ReportWorkflowException.QUOTA_TASK_CAP);
    }

    // ———— 剧本 7：真值池跨轮累积 ————

    @DisplayName("剧本7：每轮 fresh 清池后塞 2 条 → 草稿轮引用前两轮真值仍全 verified（池跨轮累积无损）")
    @Test
    void givenFreshedPoolTwoPerRound_whenSubmit_thenCrossRoundTruthsStillVerify() {
        FakeTaskRepository tasks = new FakeTaskRepository();
        RecordingSink sink = new RecordingSink();
        // 每轮 2 条：收集轮=行情两条、分析轮=估值两条、草稿轮=不相关宏观两条——
        // 草稿引用的四个数字全部来自前两轮，若快照只保最后一轮则门必 fail（未溯源越线）
        ScriptedRunner runner = ScriptedRunner.of(
                rc -> {
                    seedPool(rc,
                            truth("get_quote", "现价986.50元"),
                            truth("get_quote", "总市值8500亿"));
                    return "收集小结";
                },
                rc -> {
                    seedPool(rc,
                            truth("get_valuation", "市盈率35.40"),
                            truth("get_valuation", "市净率9.80"));
                    return "分析小结";
                },
                rc -> {
                    seedPool(rc,
                            truth("macro_brief", "{\"events_in_window\":3}"),
                            truth("macro_brief", "{\"cpi_yoy\":0.5}"));
                    return fencedReport("茅台研报", VALID_CONTENT_MD, "无");
                });
        ReportWorkflowOrchestrator orchestrator = orchestrator(runner, sink, tasks, props(), clock,
                directExecutor(), null);
        Long taskId = seedTask(tasks);

        orchestrator.submit(taskId, USER_ID, PROJECT_ID);

        assertThat(tasks.rows.get(taskId).status())
                .as("前两轮真值仍参与锚定 = 累积池跨轮无损（6 条真值全部在场）")
                .isEqualTo(ReportTask.Status.SUCCEEDED);
        List<String> tools = sink.succeeded.get(0).payload().anchors().stream()
                .map(ReportTaskSink.AnchorLine::toolName).distinct().toList();
        assertThat(tools).contains("get_quote", "get_valuation");
    }

    // ———— 剧本 8（补钉）：任务总超时 ————

    @DisplayName("剧本8：首轮推进时钟超总超时 → FAILED(TIMEOUT)，runner 只被调一次")
    @Test
    void givenClockAdvancedPastTotalTimeout_whenSubmit_thenFailedTimeout() {
        FakeTaskRepository tasks = new FakeTaskRepository();
        RecordingSink sink = new RecordingSink();
        ScriptedRunner runner = ScriptedRunner.of(
                rc -> {
                    seedPool(rc, truth("get_quote", QUOTE_TRUTH));
                    clock.set(NOW.plus(Duration.ofMinutes(6)));
                    return "收集小结";
                },
                rc -> "不应到达的分析轮");
        InvestProperties props = props();
        props.getReport().setExecution(new InvestProperties.Report.Execution(
                Duration.ofSeconds(240), Duration.ofMinutes(5), 15, 2));
        ReportWorkflowOrchestrator orchestrator = orchestrator(runner, sink, tasks, props, clock,
                directExecutor(), null);
        Long taskId = seedTask(tasks);

        orchestrator.submit(taskId, USER_ID, PROJECT_ID);

        assertThat(runner.calls()).isEqualTo(1);
        ReportTask row = tasks.rows.get(taskId);
        assertThat(row.status()).isEqualTo(ReportTask.Status.FAILED);
        assertThat(row.errorCode()).isEqualTo(ReportWorkflowException.TIMEOUT);
        assertThat(sink.failed).hasSize(1);
    }

    // ———— 剧本 9（补钉）：执行队列满 ————

    @DisplayName("剧本9：单线程真线程池被占满 → 提交同步失败落任务行 FAILED(AGENT_ERROR,队列已满)，runner 零调用")
    @Test
    void givenOccupiedSingleThreadExecutor_whenSubmit_thenTaskFailedWithQueueFull()
            throws InterruptedException {
        FakeTaskRepository tasks = new FakeTaskRepository();
        RecordingSink sink = new RecordingSink();
        ScriptedRunner runner = ScriptedRunner.of(rc -> "不应执行");
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.execute(() -> {
            occupied.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(occupied.await(5, TimeUnit.SECONDS)).isTrue();
        ReportWorkflowOrchestrator orchestrator = orchestrator(runner, sink, tasks, props(), clock,
                executor, null);
        Long taskId = seedTask(tasks);

        orchestrator.submit(taskId, USER_ID, PROJECT_ID);

        assertThat(runner.calls()).isZero();
        ReportTask row = tasks.rows.get(taskId);
        assertThat(row.status()).isEqualTo(ReportTask.Status.FAILED);
        assertThat(row.errorCode()).isEqualTo(ReportWorkflowException.AGENT_ERROR);
        assertThat(row.errorMessage()).contains("队列已满");
        assertThat(sink.failed).hasSize(1);
        assertThat(sink.failed.get(0).errorCode()).isEqualTo(ReportWorkflowException.AGENT_ERROR);
        release.countDown();
        executor.shutdown();
    }

    // ———— 补钉：asOf best-effort 解析（AnchorLine.asOf 的 Instant 口径） ————

    @DisplayName("asOf 解析：日期时间/纯日期/月度期别可解（Asia/Shanghai 口径），残串 null")
    @Test
    void givenVariousAsOfText_whenParseAsOf_thenInstantOrNull() {
        assertThat(ReportWorkflowOrchestrator.parseAsOf("2026-10-11 09:00:00"))
                .isEqualTo(Instant.parse("2026-10-11T01:00:00Z"));
        assertThat(ReportWorkflowOrchestrator.parseAsOf("2026-10-05"))
                .isEqualTo(Instant.parse("2026-10-04T16:00:00Z"));
        assertThat(ReportWorkflowOrchestrator.parseAsOf("2026-10"))
                .isEqualTo(Instant.parse("2026-09-30T16:00:00Z"));
        assertThat(ReportWorkflowOrchestrator.parseAsOf("not-a-date")).isNull();
        assertThat(ReportWorkflowOrchestrator.parseAsOf(null)).isNull();
    }

    // ———— 脚手架 ————

    private ReportWorkflowOrchestrator orchestrator(ScriptedRunner runner, RecordingSink sink,
            FakeTaskRepository tasks, InvestProperties props, Clock clock, TaskExecutor executor,
            AtomicReference<ReportUsageHook> capturedHook) {
        ResearchProjectRepository projectRepository = projectRepository();
        ReportAgentRunnerFactory runnerFactory = (userId, usageHook) -> {
            if (capturedHook != null) {
                capturedHook.set(usageHook);
            }
            return runner;
        };
        return new ReportWorkflowOrchestrator(tasks, projectRepository, sink, runnerFactory,
                executor, props, clock);
    }

    private static ResearchProjectRepository projectRepository() {
        return new ResearchProjectRepository() {
            private final ResearchProject project = ResearchProject.reconstitute(
                    PROJECT_ID, USER_ID, "600519", "贵州茅台", "BK0477", "茅台深度研究",
                    ResearchStage.NEW_ANALYSIS, ProjectStatus.ACTIVE, true, 0L, NOW, NOW);

            @Override
            public Optional<ResearchProject> findById(Long id) {
                return PROJECT_ID.equals(id) ? Optional.of(project) : Optional.empty();
            }

            @Override public List<ResearchProject> findByUserId(Long userId, ProjectStatus status) {
                throw new UnsupportedOperationException();
            }

            @Override public List<ResearchProject> findAllActiveByStage(ResearchStage stage) {
                throw new UnsupportedOperationException();
            }

            @Override public ResearchProject save(ResearchProject project) {
                throw new UnsupportedOperationException();
            }

            @Override public Map<ResearchStage, StageCompletionService.ManualState> findManualStates(Long projectId) {
                throw new UnsupportedOperationException();
            }

            @Override public void saveManualState(Long projectId, ResearchStage stage,
                    StageCompletionService.ManualState manualState) {
                throw new UnsupportedOperationException();
            }

            @Override public Optional<StrategyDoc> findStrategy(Long projectId) {
                throw new UnsupportedOperationException();
            }

            @Override public StrategyDoc saveStrategy(StrategyDoc doc) {
                throw new UnsupportedOperationException();
            }

            @Override public List<Falsifier> findFalsifiers(Long strategyId) {
                throw new UnsupportedOperationException();
            }

            @Override public void saveFalsifiers(Long strategyId, List<Falsifier> falsifiers) {
                throw new UnsupportedOperationException();
            }
        };
    }

    /** props：stateRoot 指向 @TempDir（Ruling-5 终态清理的断言面），其余默认档。 */
    private InvestProperties props() {
        InvestProperties props = new InvestProperties();
        props.getMcp().getHarness().setStateRoot(tempDir.resolve("state").toString());
        return props;
    }

    private Long seedTask(FakeTaskRepository tasks) {
        ReportTask saved = tasks.save(ReportTask.create(USER_ID, PROJECT_ID, NOW));
        return saved.id();
    }

    /** 预置 report-<taskId> 的 state 会话目录（模拟三轮会话落盘），终态断言其被清理。 */
    private void seedStateFile(Long taskId) throws IOException {
        Path sessionDir = stateDir(taskId);
        Files.createDirectories(sessionDir);
        Files.writeString(sessionDir.resolve("agent_state.json"), "{\"messages\":[]}");
    }

    private Path stateDir(Long taskId) {
        return tempDir.resolve("state").resolve(String.valueOf(USER_ID)).resolve("report-" + taskId);
    }

    private static TaskExecutor directExecutor() {
        return task -> task.run();
    }

    private static ToolInvocation truth(String toolName, String resultText) {
        return new ToolInvocation(toolName, Map.of("code", "600519"), resultText, List.of(),
                "2026-10-11 09:00:00", ToolInvocation.AsOfKind.DATA, false, false, 120L);
    }

    /** 模拟一轮：PRE_CALL fresh 清池（Ruling-1 生产时序）→ 装载本轮真值（反射：trust 包私有成员）。 */
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

    /** 向 per-task 用量钩子累计 tokens（沿 ReportUsageHook 事件源：POST_REASONING 携带 usage）。 */
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

    /** 内存任务仓库：save 赋 id 回存（reconstitute），终态断言读 rows。 */
    private static final class FakeTaskRepository implements ReportTaskRepository {
        private final Map<Long, ReportTask> rows = new LinkedHashMap<>();
        private long seq = 100;

        @Override
        public ReportTask save(ReportTask task) {
            ReportTask stored = task;
            if (task.id() == null) {
                long id = ++seq;
                stored = ReportTask.reconstitute(id, task.userId(), task.projectId(), task.status(),
                        task.stage(), task.tokenUsage(), task.errorCode(), task.errorMessage(),
                        task.createdAt(), task.updatedAt(), task.startedAt(), task.finishedAt());
            }
            rows.put(stored.id(), stored);
            return stored;
        }

        @Override
        public Optional<ReportTask> findById(Long id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<ReportTask> findLatestByProjectId(Long projectId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ReportTask> findActiveAll() {
            throw new UnsupportedOperationException();
        }
    }

    /** 落库端口记录器：按调用序捕获回调参数。 */
    private static final class RecordingSink implements ReportTaskSink {
        private final List<SucceededCall> succeeded = new ArrayList<>();
        private final List<FailedCall> failed = new ArrayList<>();

        record SucceededCall(Long taskId, long tokenUsage, SucceededPayload payload) {}

        record FailedCall(Long taskId, long tokenUsage, String errorCode, String errorMessage) {}

        @Override
        public void onTaskSucceeded(Long taskId, long tokenUsage, SucceededPayload payload) {
            succeeded.add(new SucceededCall(taskId, tokenUsage, payload));
        }

        @Override
        public void onTaskFailed(Long taskId, long tokenUsage, String errorCode, String errorMessage) {
            failed.add(new FailedCall(taskId, tokenUsage, errorCode, errorMessage));
        }
    }

    /** 剧本 runner：按调用序回放（每步收到 rc 可注真值/充电量/推时钟）；剧本耗尽即抛（未预期轮转 AGENT_ERROR）。 */
    private static final class ScriptedRunner implements ReportAgentRunner {
        private final List<Function<RuntimeContext, String>> script;
        private final List<String> prompts = new ArrayList<>();
        private int calls;

        static ScriptedRunner of(@SuppressWarnings("unchecked") Function<RuntimeContext, String>... steps) {
            return new ScriptedRunner(List.of(steps));
        }

        private ScriptedRunner(List<Function<RuntimeContext, String>> script) {
            this.script = script;
        }

        int calls() {
            return calls;
        }

        List<String> prompts() {
            return prompts;
        }

        @Override
        public String call(RuntimeContext rc, String userPrompt) {
            prompts.add(userPrompt);
            if (calls >= script.size()) {
                throw new RejectedExecutionException("剧本耗尽：第 " + (calls + 1) + " 轮未被预期");
            }
            return script.get(calls++).apply(rc);
        }
    }

    /** 可推时钟（剧本 8 总超时用）。 */
    private static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void set(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
