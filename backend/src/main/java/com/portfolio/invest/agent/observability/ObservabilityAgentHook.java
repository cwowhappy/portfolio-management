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
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.ErrorEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PostSummaryEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.hook.RuntimeContextAware;
import io.agentscope.core.message.Msg;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 轮级观测钩子（MS-30 B6，设计规格 §5.2）：挂在 HarnessAgent（与 {@link TrustAgentHook} 同漏斗，
 * {@code HarnessAgentFactory.build()} 双 hook 装配），回合收尾把该轮时延/token/trust 信号并集与
 * 工具调用观测经 {@link ObservabilityRecorder#recordTurn} 一次批量落两表（1+N，Task 8 端口）。
 *
 * <p><strong>事件契约（javap 实证，agentscope-core 2.0.3）：</strong>
 * <ul>
 *   <li><strong>PRE_CALL</strong>：重置回合累计 + 记 {@code System.nanoTime()} 起点入 RuntimeContext
 *       typed（键 {@link #RC_TURN_START_NANOS}，私有前缀 {@code observability.}，随回合隔离）；
 *       同时快照 trust 的 {@code lastReport()} 引用作 identity 护栏基线。</li>
 *   <li><strong>POST_REASONING / POST_SUMMARY</strong>：逐份累加 {@code getReasoningMessage()/
 *       getSummaryMessage().getUsage()}——<strong>每次模型调用一个 Msg、无整回合聚合 API</strong>，
 *       逐轮累加是唯一正确口径。token 口径钉死：prompt=inputTokens 原值、completion=outputTokens
 *       原值、total=两者之和（库内 {@code ChatUsage.getTotalTokens()} 即此和，cachedTokens 是否已含
 *       于 input 由模型侧决定，累计不重算不另计）；全程无 usage → 三分量 null（读端口口径：缺失
 *       行计数不进均值插值）。</li>
 *   <li><strong>POST_CALL</strong>：正常轮唯一落库锚点。库内 {@code AgentBase} 错误路
 *       （{@code createErrorHandler → notifyError → Mono.error 重抛}）<strong>不触发 POST_CALL</strong>，
 *       故 failed 轮由 <strong>ERROR</strong> 事件直接落库（failed=true + {@code getError()} 摘要
 *       截断 {@link #ERROR_SUMMARY_MAX_CHARS} 码点，全文走日志）；两锚点由回合标记防双写。</li>
 * </ul>
 *
 * <p><strong>装配序（§5.2「hook 注册序保证 trust 先行」）：</strong>库内 {@code HOOK_COMPARATOR =
 * Comparator.comparingInt(Hook::priority)} <strong>升序</strong>、{@code Hook.priority()} 缺省 100
 * （{@link TrustAgentHook} 未覆写），本 hook 覆写 {@link #priority()} 为 200——大者后行，保证
 * trust 的 POST_REASONING/POST_CALL 兜底处理先于本 hook 读取 {@code lastReport()}（trust 兜底锚点
 * 同在 POST_CALL，晚于它即读到上一轮陈旧报告）。
 *
 * <p><strong>trust 并集：</strong>消费 {@link TrustAgentHook#lastReport()}（设计规格 §5.2 允许的
 * 直读口）六键标量子集：verified/sourced/unverified/corrections/correctionFailures/exempted。
 * identity 护栏：PRE_CALL 快照引用，收尾时未换新（本轮 trust 无末轮处理，如 error 轮）→
 * trustStats=null（TurnObservation 契约「null = 该轮无 trust 信号」），防跨轮陈旧归因。
 * 值域保持<strong>标量 Map</strong>（Task 8 审查传导约束：JSONB 序列化失败防护）。</p>
 *
 * <p><strong>工具观测数据源 = {@code TrustContext.current(rc).invocations()}（当轮实录）</strong>
 * ——历史池 Msg metadata 不进观测（Task 8 审查推演闭合的口径）。calledAt best-effort 回解：CALL 线
 * asOf 即调用时刻（decorator 契约格式 Asia/Shanghai），DATA/GENERATED 为数据时点不可作调用时刻，
 * 回退回合收尾 {@code Instant.now(clock)}。</p>
 *
 * <p><strong>降级（§5.3/Review Focus #3）：观测旁路自身失效不得阻断对话</strong>——onEvent 整体
 * try/catch 仅 ERROR 日志，事件原样放行（{@code Mono.just(event)}）；端口实现侧另有自吞（Task 8），
 * 双层防御。Hook API 2.0.3 标记 forRemoval，仓库钉版 2.0.3——沿 {@link TrustAgentHook} 同款取舍。</p>
 */
public class ObservabilityAgentHook implements Hook, RuntimeContextAware {

    /** PRE_CALL 时延起点在 RuntimeContext 的键（私有前缀 observability.，按回合隔离）。 */
    static final String RC_TURN_START_NANOS = "observability.turnStartNanos";

    /** failed 轮错误摘要上限（码点）：human-facing 摘要，全文走 ERROR 日志。 */
    static final int ERROR_SUMMARY_MAX_CHARS = 200;

    /** CALL 线 asOf 格式（与 RecordingAgentToolDecorator.CALL_TIME 同款，Asia/Shanghai）——calledAt 回解。 */
    private static final DateTimeFormatter CALL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final Logger log = LoggerFactory.getLogger(ObservabilityAgentHook.class);

    private final ObservabilityRecorder recorder;
    private final TrustAgentHook trustHook;
    private final ObjectMapper mapper;
    private final Clock clock;

    private volatile RuntimeContext runtimeContext;
    /** rc 缺席（防御路径）时的回合起点兜底。 */
    private volatile Long fallbackStartNanos;
    /** 回合开始时 trust 报告快照（identity 护栏基线，防跨轮陈旧归因）。 */
    private volatile TrustTurnReport turnStartReport;
    /** 单回合单次落库（ErrorEvent 与 PostCallEvent 理论互斥，防御双发）。 */
    private volatile boolean turnRecorded;
    private long promptTokens;
    private long completionTokens;
    private boolean usageSeen;

    public ObservabilityAgentHook(ObservabilityRecorder recorder, TrustAgentHook trustHook,
            ObjectMapper mapper) {
        this(recorder, trustHook, mapper, Clock.systemUTC());
    }

    ObservabilityAgentHook(ObservabilityRecorder recorder, TrustAgentHook trustHook,
            ObjectMapper mapper, Clock clock) {
        this.recorder = recorder;
        this.trustHook = trustHook;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * 后行序：缺省 100（trust 未覆写）之后——库内升序比较器取大者后行，
     * 保证 POST_CALL 时 trust（含其兜底锚点）已先行处理完本回合（类注释「装配序」）。
     */
    @Override
    public int priority() {
        return 200;
    }

    @Override
    public void setRuntimeContext(RuntimeContext rc) {
        this.runtimeContext = rc;
    }

    @Override
    @SuppressWarnings("deprecation") // Hook 系 2.0.3 标记 forRemoval，钉版使用（TrustAgentHook 同款取舍）
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        try {
            if (event instanceof PreCallEvent pre) {
                onPreCall();
            } else if (event instanceof PostReasoningEvent reasoning) {
                accumulate(reasoning.getReasoningMessage());
            } else if (event instanceof PostSummaryEvent summary) {
                accumulate(summary.getSummaryMessage());
            } else if (event instanceof PostCallEvent call) {
                record(call.getFinalMessage(), false, null);
            } else if (event instanceof ErrorEvent error) {
                record(null, true, error.getError());
            }
        } catch (Exception e) {
            // 降级（Review Focus #3）：观测旁路自身失效仅 ERROR 留痕，事件原样放行（对话不断）
            log.error("observability.hook: 轮级采集失败已吞并（error={}）", e.getMessage(), e);
        }
        return Mono.just(event);
    }

    // ———— 回合边界 ————

    private void onPreCall() {
        turnRecorded = false;
        promptTokens = 0;
        completionTokens = 0;
        usageSeen = false;
        turnStartReport = trustHook == null ? null : trustHook.lastReport();
        long start = System.nanoTime();
        if (runtimeContext != null) {
            runtimeContext.put(RC_TURN_START_NANOS, start);
        }
        fallbackStartNanos = start;
    }

    // ———— usage 逐轮累计（每次模型调用一个 Msg） ————

    private void accumulate(Msg msg) {
        if (msg == null || msg.getUsage() == null) {
            return;
        }
        promptTokens += msg.getUsage().getInputTokens();
        completionTokens += msg.getUsage().getOutputTokens();
        usageSeen = true;
    }

    // ———— 回合收尾（POST_CALL 正常轮 / ERROR failed 轮，防双写） ————

    private void record(Msg finalMessage, boolean failed, Throwable error) {
        if (turnRecorded) {
            return;
        }
        turnRecorded = true;
        RuntimeContext rc = runtimeContext;
        Long userId = userId(rc);
        String conversationId = rc == null ? null : rc.getSessionId();
        String messageId = messageId(rc, finalMessage);
        List<ToolCallObservation> tools = mapTools(rc, userId, conversationId, messageId);
        TurnObservation turn = new TurnObservation(
                userId,
                conversationId,
                messageId,
                usageSeen ? (int) promptTokens : null,
                usageSeen ? (int) completionTokens : null,
                usageSeen ? (int) (promptTokens + completionTokens) : null,
                durationMs(),
                tools.size(),
                failed,
                errorSummary(error),
                trustStats(),
                Instant.now(clock));
        recorder.recordTurn(turn, tools);
    }

    /** trust 并集六键标量子集；本轮 trust 无新报告（identity 未换新）→ null（不陈旧归因）。 */
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

    /** 当轮工具实录 → 观测行（数据源只认 TrustContext 当轮池，历史池 metadata 不进观测）。 */
    private List<ToolCallObservation> mapTools(RuntimeContext rc, Long userId, String conversationId,
            String messageId) {
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

    /** args 序列化（域端口不引 Jackson，序列化归本调用方）；失败降级 NULL 不丢整行观测。 */
    private String argsJson(ToolInvocation invocation) {
        Map<String, Object> args = invocation.args();
        if (args == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(args);
        } catch (Exception e) {
            log.warn("observability.hook: 工具参数序列化失败已降级 NULL（tool={}, error={}）",
                    invocation.toolName(), e.getMessage());
            return null;
        }
    }

    /** calledAt best-effort：CALL 线 asOf 即调用时刻（decorator 契约格式），其余回退回合收尾时钟。 */
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

    /** 时延：PRE_CALL nanoTime 起点折毫秒（rc 优先、实例字段兜底；无起点 null；下限 0 防时钟回退）。 */
    private Long durationMs() {
        Long start = runtimeContext == null ? null : runtimeContext.get(RC_TURN_START_NANOS);
        if (start == null) {
            start = fallbackStartNanos;
        }
        if (start == null) {
            return null;
        }
        return Math.max(0L, (System.nanoTime() - start) / 1_000_000L);
    }

    // ———— 标识符（rc sessionId/userId + 线上 messageId 优先，TrustAgentHook :297-305 同款判定） ————

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
