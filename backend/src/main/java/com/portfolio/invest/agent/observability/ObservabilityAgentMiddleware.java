package com.portfolio.invest.agent.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.agent.trust.ToolInvocation;
import com.portfolio.invest.agent.trust.TrustAgentHook;
import com.portfolio.invest.agent.trust.TrustContext;
import com.portfolio.invest.agent.trust.TrustTurnReport;
import com.portfolio.invest.agent.trust.TrustWireMessageIdMiddleware;
import com.portfolio.invest.domain.observability.ObservabilityRecorder;
import com.portfolio.invest.domain.observability.ToolCallObservation;
import com.portfolio.invest.domain.observability.TurnObservation;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatUsage;
import io.micrometer.core.instrument.Metrics;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 轮级观测中间件（hook-migration，自 ObservabilityAgentHook 等价迁移）：单点 onAgent 拦截
 * ——入口回合重置与起点、doOnNext 过滤 ModelCallEndEvent（usage 双路累计）与
 * AgentResultEvent（正常轮落库）、doOnError（failed 轮落库）。设计规格 §2.1 映射表。
 *
 * <p><strong>装配序机制替换：</strong>旧 Hook 依赖 priority=200 保证晚于 trust 读取
 * lastReport()；中间件在 onAgent 链外侧，完成信号天然晚于核心内 Trust hook 的 POST_CALL
 * ——无 priority、注册序不敏感（设计规格 §一事实 1）。
 *
 * <p><strong>有意简化（设计规格 §三.1）：</strong>时延起点 RC typed 属性取消，降级实例
 * 字段 startNanos（原 fallbackStartNanos 转正；全仓无跨组件读者）。
 *
 * <p><strong>降级红线（NFR-3）：</strong>全部拦截逻辑异常吞并留痕（观测 ERROR/真值 WARN），
 * 事件原样放行；doOnError 落库异常不得覆盖原始 Agent 错误。
 */
public final class ObservabilityAgentMiddleware implements MiddlewareBase {

    /** failed 轮错误摘要上限（码点）：human-facing 摘要，全文走 ERROR 日志。 */
    static final int ERROR_SUMMARY_MAX_CHARS = 200;

    /**
     * 吞并失败计数器名（审查 2026-10-12 §三.8：观测系统自身的失败被静默时必须有度量）：
     * {@code stage} 标签区分采集（collection）与落库（record）两处吞并点。经 Micrometer
     * 全局注册表暴露——Spring Boot Actuator 将自动配置的 MeterRegistry 挂入
     * {@link Metrics#globalRegistry}，生产计数直达 /actuator/metrics；无注册表环境（单测）
     * 为 no-op 计数，行为不变。
     */
    static final String SWALLOWED_COUNTER_NAME = "invest.observability.turn.swallowed";

    private static final Logger log = LoggerFactory.getLogger(ObservabilityAgentMiddleware.class);
    private static final DateTimeFormatter CALL_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final ObservabilityRecorder recorder;
    private final TrustAgentHook trustHook;
    private final ObjectMapper mapper;
    private final Clock clock;

    private volatile boolean turnRecorded;
    private volatile TrustTurnReport turnStartReport;
    private volatile Long startNanos;
    private long promptTokens;
    private long completionTokens;
    private boolean usageSeen;

    public ObservabilityAgentMiddleware(ObservabilityRecorder recorder, TrustAgentHook trustHook,
            ObjectMapper mapper) {
        this(recorder, trustHook, mapper, Clock.systemUTC());
    }

    ObservabilityAgentMiddleware(ObservabilityRecorder recorder, TrustAgentHook trustHook,
            ObjectMapper mapper, Clock clock) {
        this.recorder = recorder;
        this.trustHook = trustHook;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        onTurnStart();
        return next.apply(input)
                .doOnNext(event -> onEvent(ctx, event))
                .doOnError(error -> safeRecord(ctx, null, true, error));
    }

    // ———— 回合边界（入口即旧 PRE_CALL：重置 + trust 报告 identity 基线 + 起点） ————

    private void onTurnStart() {
        turnRecorded = false;
        promptTokens = 0;
        completionTokens = 0;
        usageSeen = false;
        turnStartReport = trustHook == null ? null : trustHook.lastReport();
        startNanos = System.nanoTime();
    }

    // ———— 事件分流（usage 累计 / 正常轮落库） ————

    private void onEvent(RuntimeContext ctx, AgentEvent event) {
        try {
            if (event instanceof ModelCallEndEvent end) {
                accumulate(end.getUsage());
            } else if (event instanceof AgentResultEvent result) {
                safeRecord(ctx, result.getResult(), false, null);
            }
        } catch (Exception e) {
            Metrics.counter(SWALLOWED_COUNTER_NAME, "stage", "collection").increment();
            log.error("observability.middleware: 轮级采集失败已吞并（error={}）", e.getMessage(), e);
        }
    }

    private void accumulate(ChatUsage usage) {
        if (usage == null) {
            return;
        }
        promptTokens += usage.getInputTokens();
        completionTokens += usage.getOutputTokens();
        usageSeen = true;
    }

    /** 落库自吞并（Review Focus #3：doOnError 路径的异常不得覆盖原始 Agent 错误）。 */
    private void safeRecord(RuntimeContext ctx, Msg finalMessage, boolean failed, Throwable error) {
        try {
            record(ctx, finalMessage, failed, error);
        } catch (Exception e) {
            Metrics.counter(SWALLOWED_COUNTER_NAME, "stage", "record").increment();
            log.error("observability.middleware: 轮级落库失败已吞并（failed={}, error={}）",
                    failed, e.getMessage(), e);
        }
    }

    // ———— 落库组装（自 Hook record 原样迁移；runtimeContext 字段 → ctx 参数） ————

    private void record(RuntimeContext rc, Msg finalMessage, boolean failed, Throwable error) {
        if (turnRecorded) {
            return;
        }
        turnRecorded = true;
        Long userId = userId(rc);
        String conversationId = rc == null ? null : rc.getSessionId();
        String messageId = messageId(rc, finalMessage);
        List<ToolCallObservation> tools = mapTools(rc, userId, conversationId, messageId);
        TurnObservation turn = new TurnObservation(
                userId, conversationId, messageId,
                usageSeen ? (int) promptTokens : null,
                usageSeen ? (int) completionTokens : null,
                usageSeen ? (int) (promptTokens + completionTokens) : null,
                durationMs(), tools.size(), failed, errorSummary(error),
                trustStats(), Instant.now(clock));
        recorder.recordTurn(turn, tools);
    }

    /** trust 并集六键标量子集（identity 护栏：本轮 trust 无新报告 → null）——原样迁移。 */
    private Map<String, Object> trustStats() {
        if (trustHook == null) {
            return null;
        }
        TrustTurnReport report = trustHook.lastReport();
        if (report == null || report == turnStartReport) {
            return null;
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("verified", report.stats().verified());
        stats.put("sourced", report.stats().sourced());
        stats.put("unverified", report.stats().unverified());
        stats.put("corrections", report.corrections().size());
        stats.put("correctionFailures", report.correctionFailures());
        stats.put("exempted", report.exempted());
        return stats;
    }

    /** 当轮工具实录 → 观测行（TrustContext.current(rc)，历史池不进观测）——原样迁移。 */
    private List<ToolCallObservation> mapTools(RuntimeContext rc, Long userId,
            String conversationId, String messageId) {
        if (rc == null) {
            return List.of();
        }
        List<ToolInvocation> invocations = TrustContext.current(rc).invocations();
        List<ToolCallObservation> tools = new ArrayList<>(invocations.size());
        for (ToolInvocation invocation : invocations) {
            tools.add(new ToolCallObservation(
                    userId,
                    conversationId,
                    messageId,
                    invocation.toolName(),
                    argsJson(invocation),
                    invocation.resultText(),
                    invocation.emittedSpecs().size(),
                    invocation.asOf(),
                    invocation.asOfKind().wireName(),
                    invocation.mcp(),
                    invocation.failed(),
                    invocation.durationMs(),
                    calledAt(invocation)));
        }
        return tools;
    }

    /** args 序列化失败降级 NULL——原样迁移。 */
    private String argsJson(ToolInvocation invocation) {
        Map<String, Object> args = invocation.args();
        if (args == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(args);
        } catch (Exception e) {
            log.warn("observability.middleware: 工具参数序列化失败已降级 NULL（tool={}, error={}）",
                    invocation.toolName(), e.getMessage());
            return null;
        }
    }

    /** calledAt best-effort（CALL 线 asOf 即调用时刻）——原样迁移。 */
    private Instant calledAt(ToolInvocation invocation) {
        if (invocation.asOfKind() == ToolInvocation.AsOfKind.CALL) {
            try {
                return LocalDateTime.parse(invocation.asOf(), CALL_TIME).atZone(ZONE).toInstant();
            } catch (Exception ignore) {
                // 非 CALL_TIME 形态（理论不可达，decorator 恒定格式）——回退收尾时钟
            }
        }
        return Instant.now(clock);
    }

    /** 时延：startNanos 折毫秒，下限 0 防时钟回退，无起点 null（RC 键已简化为实例字段）。 */
    private Long durationMs() {
        Long start = startNanos;
        if (start == null) {
            return null;
        }
        return Math.max(0L, (System.nanoTime() - start) / 1_000_000L);
    }

    private static Long userId(RuntimeContext rc) {
        if (rc == null) {
            return null;
        }
        String userId = rc.getUserId();
        if (userId == null || userId.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(userId);
        } catch (NumberFormatException e) {
            return null; // 非数字（弱引用列容忍 null，旁路 best-effort）
        }
    }

    private static String messageId(RuntimeContext rc, Msg finalMessage) {
        if (rc != null) {
            Object wire = rc.get(TrustWireMessageIdMiddleware.RC_KEY);
            if (wire instanceof String value && !value.isBlank()) {
                return value;
            }
        }
        return finalMessage == null ? null : finalMessage.getId();
    }

    /** failed 轮错误摘要：类名+消息，码点边界截断（上限 200 + 省略号；全文在 ERROR 日志）。 */
    private static String errorSummary(Throwable error) {
        if (error == null) {
            return null;
        }
        String summary = error.getClass().getSimpleName() + ": " + error.getMessage();
        if (summary.codePointCount(0, summary.length()) <= ERROR_SUMMARY_MAX_CHARS) {
            return summary;
        }
        int cut = summary.offsetByCodePoints(0, ERROR_SUMMARY_MAX_CHARS);
        return summary.substring(0, cut) + "…";
    }
}
