package com.portfolio.invest.agent.report;

import com.portfolio.invest.agent.trust.ToolInvocation;
import com.portfolio.invest.agent.trust.TrustContext;
import com.portfolio.invest.application.research.ReportTaskSink;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.research.ReportTask;
import com.portfolio.invest.domain.research.ReportTaskRepository;
import com.portfolio.invest.domain.research.ResearchProject;
import com.portfolio.invest.domain.research.ResearchProjectRepository;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.JsonFileAgentStateStore;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

/**
 * 研报工作流编排器（MS-32 B9，设计规格 §3.2）：四阶段状态机
 * COLLECTING→ANALYZING→DRAFTING→VALIDATING（三轮 call 续会话 + 校验门），
 * 多轮驱动替代超大 maxIters（同 sessionId=report-&lt;taskId&gt; 续会话，D3 失败重来 = 新任务新会话）。
 *
 * <p><strong>每轮护栏（call 返回后）：</strong>①真值池快照累积——排空 {@link ReportUsageHook}
 * 在 POST_CALL 时点采集的真值（Ruling-21 修复③主通道：HarnessAgent 对编排器裸 rc 恒派生新实例，
 * 工具记录与 hook 同见派生 rc，编排器自有 rc 上直读恒空——S1 真 LLM 实证 + 字节码归因，详见
 * {@link ReportUsageHook} 类注释②；派生池下一轮 PRE_CALL 被 fresh 覆盖，故 hook 抢先快照、
 * 此处排空防跨轮重复，门只吃累积池）；②单任务 token 上限（ReportUsageHook 累计，
 * 严格大于即 {@code FAILED(QUOTA_TASK_CAP)}）；③任务总超时（{@code FAILED(TIMEOUT)}）。</p>
 *
 * <p><strong>VALIDATING：</strong>payload 围栏提取（格式失败 → 格式反馈重试 1 轮，整任务一次；
 * 正文超限直通 {@code FAILED(PARSE_FAILED)}）→ 校验门（fail-closed，失败反馈回炉轮
 * ≤ max-regen-rounds → 仍败 {@code FAILED(VALIDATION_FAILED)}）。</p>
 *
 * <p><strong>fail-closed：</strong>任何未捕获异常收敛 {@code FAILED(AGENT_ERROR)}——任何路径
 * 不得产出未过门研报；终态（成功/失败）回调 {@link ReportTaskSink}（B11 落研报表+配额结算；
 * 任务行终态与 tokenUsage 由本编排器先行落库）并清理 report-* state 会话文件
 * （Ruling-5：{@code AgentStateStore.delete(userId, sessionId)}，失败仅 WARN 不影响终态）。
 * 执行线程池拒绝（queueCapacity=0）→ 提交同步 {@code FAILED(AGENT_ERROR, 队列已满)}。</p>
 *
 * <p>项目信息自查（agent→domain 合法边）：stockCode/stockName/title 注入各阶段 prompt
 * （{@link ReportWorkflowPrompt} 阶段模板）；校验门以 Spring 绑定的 InvestProperties 构建
 * （Ruling-14，report 档独立容差，对话档零改动）。</p>
 */
@Component
public class ReportWorkflowOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ReportWorkflowOrchestrator.class);

    /** asOf 解析时区（沿 RecordingAgentToolDecorator/ObservabilityAgentHook 的 Asia/Shanghai 口径）。 */
    private static final ZoneId AS_OF_ZONE = ZoneId.of("Asia/Shanghai");

    private final ReportTaskRepository taskRepository;
    private final ResearchProjectRepository projectRepository;
    private final ReportTaskSink sink;
    private final ReportAgentRunnerFactory runnerFactory;
    private final TaskExecutor executor;
    private final ReportValidationGate gate;
    private final InvestProperties props;
    private final Clock clock;
    private final long perTaskTokenCap;
    private final Duration totalTimeout;
    private final int maxRegenRounds;
    private final int draftMaxBytes;

    public ReportWorkflowOrchestrator(ReportTaskRepository taskRepository,
            ResearchProjectRepository projectRepository,
            ReportTaskSink sink,
            ReportAgentRunnerFactory runnerFactory,
            @Qualifier("reportTaskExecutor") TaskExecutor executor,
            InvestProperties props,
            @Qualifier("reportWorkflowClock") Clock clock) {
        this.taskRepository = Objects.requireNonNull(taskRepository);
        this.projectRepository = Objects.requireNonNull(projectRepository);
        this.sink = Objects.requireNonNull(sink);
        this.runnerFactory = Objects.requireNonNull(runnerFactory);
        this.executor = Objects.requireNonNull(executor);
        this.props = Objects.requireNonNull(props);
        this.clock = Objects.requireNonNull(clock);
        this.gate = ReportValidationGate.forReport(props);
        this.perTaskTokenCap = props.getReport().getQuota().perTaskTokenCap();
        this.totalTimeout = props.getReport().getExecution().totalTimeout();
        this.maxRegenRounds = props.getReport().getValidation().maxRegenRounds();
        this.draftMaxBytes = props.getReport().getDraftMaxBytes();
    }

    /**
     * 提交执行（异步）：reportTaskExecutor（有界 0 队列）满即拒 → 同步落任务行
     * {@code FAILED(AGENT_ERROR, 执行队列已满)}（fail-closed，提交方无感）。
     */
    public void submit(Long taskId, Long userId, Long projectId) {
        Objects.requireNonNull(taskId, "taskId 不能为空");
        Objects.requireNonNull(userId, "userId 不能为空");
        Objects.requireNonNull(projectId, "projectId 不能为空");
        try {
            executor.execute(() -> run(taskId, userId, projectId));
        } catch (RejectedExecutionException e) {
            log.warn("研报任务 {} 提交被拒（执行线程池已满）", taskId);
            failTask(taskId, userId, new ReportUsageHook(),
                    ReportWorkflowException.AGENT_ERROR, "执行队列已满，请稍后重试");
        }
    }

    private void run(Long taskId, Long userId, Long projectId) {
        ReportUsageHook usageHook = new ReportUsageHook();
        try {
            executeWorkflow(taskId, userId, projectId, usageHook);
        } catch (ReportWorkflowException e) {
            log.warn("研报任务 {} 失败（{}）：{}", taskId, e.errorCode(), e.getMessage());
            failTask(taskId, userId, usageHook, e.errorCode(), e.getMessage());
        } catch (Exception e) {
            log.error("研报任务 {} 未预期异常", taskId, e);
            failTask(taskId, userId, usageHook, ReportWorkflowException.AGENT_ERROR, e.toString());
        } finally {
            TrustContext.reset();
        }
    }

    private void executeWorkflow(Long taskId, Long userId, Long projectId, ReportUsageHook usageHook) {
        ReportTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ReportWorkflowException(ReportWorkflowException.AGENT_ERROR,
                        "研报任务不存在：" + taskId));
        if (task.status() != ReportTask.Status.PENDING) {
            throw new ReportWorkflowException(ReportWorkflowException.AGENT_ERROR,
                    "任务非 PENDING 态拒绝执行，当前态 " + task.status());
        }
        ResearchProject project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ReportWorkflowException(ReportWorkflowException.AGENT_ERROR,
                        "研究项目不存在：" + projectId));
        task = taskRepository.save(task.markRunning(clock.instant()));
        TaskRun run = new TaskRun(task, userId, usageHook, runnerFactory.create(userId, usageHook));

        run.round(collectPrompt(project));
        run.advance(ReportTask.Stage.ANALYZING);
        run.round(analyzePrompt(project));
        run.advance(ReportTask.Stage.DRAFTING);
        String draft = run.round(draftPrompt(project));
        run.advance(ReportTask.Stage.VALIDATING);

        ResearchReportSpec.V1 spec = run.parseWithFormatRetry(draft);
        ReportValidationGate.GateResult result = gate.evaluate(spec.contentMd(),
                ReportValidationGate.extractAssumptionStatements(spec.contentMd()), run.pool);
        int regenUsed = 0;
        while (!result.passed()) {
            if (regenUsed >= maxRegenRounds) {
                throw new ReportWorkflowException(ReportWorkflowException.VALIDATION_FAILED,
                        String.join("；", result.feedbackLines()));
            }
            regenUsed++;
            String feedback = String.join("\n", result.feedbackLines());
            run.advance(ReportTask.Stage.DRAFTING);
            String regen = run.round(regenPrompt(feedback));
            run.advance(ReportTask.Stage.VALIDATING);
            spec = run.parseWithFormatRetry(regen);
            result = gate.evaluate(spec.contentMd(),
                    ReportValidationGate.extractAssumptionStatements(spec.contentMd()), run.pool);
        }

        long tokens = usageHook.totalTokens();
        taskRepository.save(run.task.succeed(tokens, clock.instant()));
        sink.onTaskSucceeded(taskId, tokens, succeededPayload(spec, result));
        cleanupState(userId, taskId);
        log.info("研报任务 {} 成功：token={}，anchors={}，assumptions={}",
                taskId, tokens, result.anchors().anchors().size(), result.bindings().size());
    }

    /**
     * 失败收尾（fail-closed）：任务行重读置 FAILED（FAILED 再失败覆盖诊断，Ruling-7）→
     * sink 失败回调（配额结算）→ 终态 state 清理；各步异常仅记日志不阻断后续步。
     */
    private void failTask(Long taskId, Long userId, ReportUsageHook usageHook,
            String errorCode, String errorMessage) {
        long tokens = usageHook.totalTokens();
        try {
            ReportTask task = taskRepository.findById(taskId).orElse(null);
            if (task == null) {
                log.error("研报任务 {} 行不存在，失败态无处落（errorCode={}）", taskId, errorCode);
            } else {
                taskRepository.save(task.fail(errorCode, errorMessage, tokens, clock.instant()));
                sink.onTaskFailed(taskId, tokens, errorCode, errorMessage);
            }
        } catch (Exception e) {
            log.error("研报任务 {} 失败收尾异常（errorCode={}）", taskId, errorCode, e);
        }
        cleanupState(userId, taskId);
    }

    /** 终态清理 report-* state 会话（Ruling-5）：同 root 自建实例（文件后端无共享态），异常仅 WARN。 */
    private void cleanupState(Long userId, Long taskId) {
        try {
            new JsonFileAgentStateStore(Paths.get(props.getMcp().getHarness().getStateRoot()))
                    .delete(userId.toString(), sessionId(taskId));
        } catch (Exception e) {
            log.warn("研报任务 {} 终态 state 清理失败（不影响任务终态）：{}", taskId, e.getMessage());
        }
    }

    private ReportTaskSink.SucceededPayload succeededPayload(ResearchReportSpec.V1 spec,
            ReportValidationGate.GateResult result) {
        List<ReportTaskSink.AnchorLine> anchors = result.anchors().anchors().stream()
                .map(a -> new ReportTaskSink.AnchorLine(a.snippet(), a.occ(), a.state().name(),
                        a.tool(), a.args(), parseAsOf(a.asOf()),
                        a.asOfKind() == null ? null : a.asOfKind().name(), a.raw()))
                .toList();
        List<ReportTaskSink.AssumptionLine> assumptions = result.bindings().stream()
                .map(b -> new ReportTaskSink.AssumptionLine(b.seq(), b.statement(), b.anchorIndex()))
                .toList();
        return new ReportTaskSink.SucceededPayload(spec.title(), result.correctedMd(),
                spec.limitations(), anchors, assumptions);
    }

    /**
     * asOf String → Instant（best-effort，B11 落库/重查均 null 安全）：≥10 位取
     * {@code yyyy-MM-dd[ HH:mm[:ss]]}（空格转 ISO 'T'，Asia/Shanghai 源站口径）；不足 10 位按
     * {@code yyyy-MM} 折算月初；不可解析 null（沿 ConfidenceScorer.parseAsOf 解析阶梯语义，
     * trust 包私有不可直引故就地同构）。
     */
    static Instant parseAsOf(String asOf) {
        if (asOf == null || asOf.isBlank()) {
            return null;
        }
        String text = asOf.strip();
        try {
            if (text.length() >= 10) {
                String normalized = text.substring(0, Math.min(text.length(), 19)).replace(' ', 'T');
                if (normalized.length() == 10) {
                    return LocalDate.parse(normalized).atStartOfDay(AS_OF_ZONE).toInstant();
                }
                return LocalDateTime.parse(normalized).atZone(AS_OF_ZONE).toInstant();
            }
            return YearMonth.parse(text).atDay(1).atStartOfDay(AS_OF_ZONE).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String sessionId(Long taskId) {
        return "report-" + taskId;
    }

    private static String collectPrompt(ResearchProject project) {
        return String.format(ReportWorkflowPrompt.COLLECT_PROMPT_TEMPLATE,
                project.stockName(), project.stockCode(), project.title());
    }

    private static String analyzePrompt(ResearchProject project) {
        return String.format(ReportWorkflowPrompt.ANALYZE_PROMPT_TEMPLATE,
                project.stockName(), project.stockCode(), project.title());
    }

    private static String draftPrompt(ResearchProject project) {
        return String.format(ReportWorkflowPrompt.DRAFT_PROMPT_TEMPLATE,
                project.stockName(), project.stockCode(), project.title());
    }

    private static String regenPrompt(String feedback) {
        return String.format(ReportWorkflowPrompt.REGEN_PROMPT_TEMPLATE, feedback);
    }

    /** 单任务运行态：task 行引用随阶段推进回存；池跨轮累积；双上限卡点统一在每轮后。 */
    private final class TaskRun {
        private final RuntimeContext rc;
        private final ReportAgentRunner runner;
        private final ReportUsageHook usageHook;
        private final List<ToolInvocation> pool = new ArrayList<>();
        private final Instant taskStart;
        private ReportTask task;
        private boolean formatRetryAvailable = true;

        TaskRun(ReportTask task, Long userId, ReportUsageHook usageHook, ReportAgentRunner runner) {
            this.task = task;
            this.usageHook = usageHook;
            this.runner = runner;
            this.taskStart = clock.instant();
            this.rc = RuntimeContext.builder().userId(userId.toString())
                    .sessionId(sessionId(task.id())).build();
        }

        /** 单轮调用：返回最终消息文本；返回后排空 hook 采集真值 + 双上限卡点（§3.2 全程护栏）。 */
        String round(String prompt) {
            String text = runner.call(rc, prompt);
            pool.addAll(usageHook.drainTruthInvocations());
            checkCaps();
            return text;
        }

        /** 阶段推进落行（同阶段幂等不回存）；回炉轮回退 DRAFTING 再进 VALIDATING 均走此口。 */
        void advance(ReportTask.Stage stage) {
            ReportTask next = task.markStage(stage, clock.instant());
            if (next != task) {
                task = taskRepository.save(next);
            }
        }

        /**
         * payload 提取（格式反馈重试 ≤1 轮，整任务一次）：围栏/JSON/字段失败重试一回，
         * 再败或正文超限（计费留痕口径，B6 契约）直通 {@code PARSE_FAILED}。
         */
        ResearchReportSpec.V1 parseWithFormatRetry(String text) {
            try {
                return ReportPayloadParser.parse(text, draftMaxBytes);
            } catch (ReportPayloadException first) {
                if (first.kind() == ReportPayloadException.Kind.CONTENT_OVERSIZE
                        || !formatRetryAvailable) {
                    throw new ReportWorkflowException(ReportWorkflowException.PARSE_FAILED,
                            first.getMessage());
                }
                formatRetryAvailable = false;
                advance(ReportTask.Stage.DRAFTING);
                String retry = round(regenPrompt("研报格式不合格：" + first.getMessage()));
                advance(ReportTask.Stage.VALIDATING);
                try {
                    return ReportPayloadParser.parse(retry, draftMaxBytes);
                } catch (ReportPayloadException second) {
                    throw new ReportWorkflowException(ReportWorkflowException.PARSE_FAILED,
                            second.getMessage());
                }
            }
        }

        private void checkCaps() {
            long tokens = usageHook.totalTokens();
            if (tokens > perTaskTokenCap) {
                throw new ReportWorkflowException(ReportWorkflowException.QUOTA_TASK_CAP,
                        "单任务 token 消耗 " + tokens + " 超上限 " + perTaskTokenCap);
            }
            if (Duration.between(taskStart, clock.instant()).compareTo(totalTimeout) > 0) {
                throw new ReportWorkflowException(ReportWorkflowException.TIMEOUT,
                        "任务总时长超过上限 " + totalTimeout);
            }
        }
    }
}
