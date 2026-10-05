package com.portfolio.invest.agent.trust;

import com.portfolio.invest.config.InvestProperties;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PostSummaryEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.hook.RuntimeContextAware;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 可信溯源回合钩子（MS-29 B5，设计规格 §4.2/§4.3）：挂在 HarnessAgent（AG-UI 与飞书共同漏斗，
 * {@code HarnessAgentFactory.build()} 经 Builder {@code .hook()} 装配），回合收尾跑校验锚定流水并
 * 发 Custom 事件。
 *
 * <p><strong>锚点取舍（Hook vs Middleware + 三事件锚定，B0/B5 双探针在 HarnessAgent 生产路径实证）：</strong>
 * <ul>
 *   <li><strong>POST_REASONING 末轮为主锚点</strong>——唯一能把改写同时送达 stateStore 与返回值的
 *       官方口：库内 {@code lambda$runPostReasoningPipeline$19} 在 hook 链之后才把
 *       {@code getReasoningMessage()} 写入 state.contextMutable（落盘在其后），call()/stream 返回值
 *       同源。末轮判定 = 无 ToolUseBlock 且文本非空（工具轮 POST_REASONING 不处理）。</li>
 *   <li><strong>POST_SUMMARY 为辅</strong>（maxIters 耗尽收尾，B0 证实裸 ReActAgent、B5 证实
 *       HarnessAgent 路径均触发）：{@code lambda$summarizing$64} 同样在 hook 后消费
 *       {@code getSummaryMessage()} 落 state。</li>
 *   <li><strong>POST_CALL 兜底 + 回合收尾</strong>：正常路径 state 已在 doCall 内落盘（早于
 *       POST_CALL），{@code setFinalMessage} 只能改返回值半边——故仅当本回合流水未跑过时兜底
 *       （INFO 留痕 state 未改写），并恒定承担 {@code TrustContext.reset()}（ThreadLocal 回退通道
 *       清理，CurrentUserHolder 先例）。</li>
 *   <li><strong>幂等防重</strong>：同回合三锚点任一处理后置标记，PRE_CALL 重置（ruling 1）。</li>
 *   <li>Middleware 路线（无弃用标记）只能改事件流、够不到 state/返回值的 Msg 改写——不采用，
 *       详见任务报告取舍记录。Hook API 2.0.3 标记 forRemoval，仓库钉版 2.0.3，升级时需随上游
 *       替代口迁移。</li>
 * </ul>
 *
 * <p><strong>事件契约（§4.3）：</strong>{@code trust.correction}（有大偏差修正时，每条替换一个事件，
 * 先发）value={messageId, snippet, occ, replacement, note}；{@code trust.anchors}（每 assistant
 * 末轮一次）value={messageId, payload}（payload v1 见 {@link TrustTurnReport#toPayload()}，
 * advice/confidence 键由 B6/B7 补）。messageId 取 {@link TrustWireMessageIdMiddleware} 观察到的
 * AG-UI 线上 id（TEXT_MESSAGE 的 replyId，观察缺席回退 {@link Msg#getId()}——线上同源）。发射经
 * {@code deferContextual → AgentEventEmitter.fromContext}（B0 探针 2），emitter 不可得时静默跳过
 * （飞书 call() 路无消费者，不报错）。
 *
 * <p><strong>护栏（决策：宁可少标不可断流）：</strong>onEvent 整体 try-catch，异常 → 原文直通 +
 * WARN 日志，SSE 不中断。留痕：每处理回合一条结构化 INFO（字段名供 MS-30 看板取数，见
 * {@code logTurn}）。
 */
public class TrustAgentHook implements Hook, RuntimeContextAware {

    /** 跨轮数值池的 Msg metadata 键（§2.2；{@code _chat_usage} 先例风格）。 */
    public static final String POOL_METADATA_KEY = "_trust_pool";

    public static final String ANCHORS_EVENT = "trust.anchors";
    public static final String CORRECTION_EVENT = "trust.correction";

    /** 豁免回看的近期 user 消息条数上限（决策 #16「近期」口径）。 */
    static final int RECENT_USER_TEXTS_LIMIT = 5;

    private static final Logger log = LoggerFactory.getLogger(TrustAgentHook.class);

    private final TrustTurnProcessor processor;
    private volatile RuntimeContext runtimeContext;
    /** 幂等防重标记：同回合 POST_REASONING/POST_SUMMARY/POST_CALL 只处理一次。 */
    private volatile boolean turnProcessed;
    private final List<String> turnUserTexts = new ArrayList<>();
    /** 最近一次处理产物（留痕/测试观测）。 */
    private volatile TrustTurnReport lastReport;

    public TrustAgentHook(InvestProperties.Trust settings) {
        this(new TrustTurnProcessor(settings));
    }

    public TrustAgentHook(TrustTurnProcessor processor) {
        this.processor = processor;
    }

    public TrustTurnReport lastReport() {
        return lastReport;
    }

    @Override
    public void setRuntimeContext(RuntimeContext rc) {
        this.runtimeContext = rc;
    }

    @Override
    @SuppressWarnings("deprecation") // Hook 系 2.0.3 标记 forRemoval，钉版使用（类注释取舍记录）
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        try {
            if (event instanceof PreCallEvent pre) {
                onPreCall(pre);
                return Mono.just(event);
            }
            if (event instanceof PostReasoningEvent post) {
                return onFinalRound(post.getReasoningMessage(), post::setReasoningMessage, event);
            }
            if (event instanceof PostSummaryEvent summary) {
                return onFinalRound(summary.getSummaryMessage(), summary::setSummaryMessage, event);
            }
            if (event instanceof PostCallEvent call) {
                return onPostCall(call, event);
            }
            return Mono.just(event);
        } catch (Exception e) {
            log.warn("trust.guardrail: 回合校验异常，原文直通（userId={}, error={}）",
                    runtimeContext == null ? "?" : runtimeContext.getUserId(), e.getMessage(), e);
            return Mono.just(event);
        }
    }

    // ———— 回合边界 ————

    private void onPreCall(PreCallEvent event) {
        turnProcessed = false;
        synchronized (turnUserTexts) {
            turnUserTexts.clear();
            for (Msg msg : event.getInputMessages()) {
                if (msg.getRole() == MsgRole.USER
                        && msg.getTextContent() != null && !msg.getTextContent().isBlank()) {
                    turnUserTexts.add(msg.getTextContent());
                }
            }
        }
        // 回合开始：清 ThreadLocal 残留 + rc 预挂新池（工具调用与 hook 命中同一池，B3 接线建议）
        TrustContext.reset();
        if (runtimeContext != null) {
            TrustContext.fresh(runtimeContext);
        }
    }

    // ———— 末轮处理（POST_REASONING 主 / POST_SUMMARY 辅） ————

    private <T extends HookEvent> Mono<T> onFinalRound(
            Msg original, Consumer<Msg> rewriter, T event) {
        if (turnProcessed || !isFinalTextRound(original)) {
            return Mono.just(event);
        }
        TrustTurnReport report = processTurn(original);
        applyRewrite(original, rewriter, report);
        turnProcessed = true;
        lastReport = report;
        logTurn("final", messageId(original), report);
        return emitEvents(messageId(original), report, event);
    }

    // ———— POST_CALL 兜底 + 回合收尾 ————

    private <T extends HookEvent> Mono<T> onPostCall(PostCallEvent call, T event) {
        if (!turnProcessed && isFinalTextRound(call.getFinalMessage())) {
            // 兜底路径（正常不应到达）：state 已在 doCall 内落盘，setFinalMessage 仅达返回值半边
            log.info("trust.fallback: POST_CALL 兜底处理（stateStore 未改写），messageId={}",
                    call.getFinalMessage().getId());
            TrustTurnReport report = processTurn(call.getFinalMessage());
            applyRewrite(call.getFinalMessage(), call::setFinalMessage, report);
            turnProcessed = true;
            lastReport = report;
            logTurn("fallback", messageId(call.getFinalMessage()), report);
            return emitEvents(messageId(call.getFinalMessage()), report, event);
        }
        // 回合收尾：清 ThreadLocal 回退通道（rc 通道天然按回合隔离）
        TrustContext.reset();
        return Mono.just(event);
    }

    // ———— 处理与改写 ————

    /** 末轮文本判定：非 null、无 ToolUseBlock（工具轮不处理）、文本非空。 */
    private static boolean isFinalTextRound(Msg msg) {
        return msg != null
                && msg.getContentBlocks(ToolUseBlock.class).isEmpty()
                && msg.getTextContent() != null
                && !msg.getTextContent().isBlank();
    }

    private TrustTurnReport processTurn(Msg original) {
        List<ToolInvocation> currentPool = runtimeContext == null
                ? List.of() : TrustContext.current(runtimeContext).invocations();
        return processor.process(
                original.getTextContent(),
                currentPool,
                historyPool(),
                recentUserTexts());
    }

    /**
     * 改写应用：文本被修正或池摘要非空时，以保留 id/name/role/timestamp/usage 的重建 Msg
     * 调 rewriter（stateStore 与返回值同源；metadata 携带 §2.2 池摘要）。
     * 文本块整体替换为修正后全文（末轮文本通常单块）；<strong>非文本块（如 thinking）原样保留</strong>
     * ——DeepSeek reasoner 的思考内容不因校验改写丢失。
     */
    private void applyRewrite(Msg original, Consumer<Msg> rewriter, TrustTurnReport report) {
        if (!report.rewritten() && report.poolSummary().isEmpty()) {
            return;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(original.getMetadata() == null
                ? Map.of() : original.getMetadata());
        if (!report.poolSummary().isEmpty()) {
            metadata.put(POOL_METADATA_KEY, report.poolSummary());
        }
        List<ContentBlock> content = new ArrayList<>();
        for (ContentBlock block : original.getContent()) {
            if (!(block instanceof TextBlock)) {
                content.add(block);
            }
        }
        content.add(TextBlock.builder().text(report.correctedText()).build());
        Msg rewritten = Msg.builder()
                .id(original.getId())
                .name(original.getName())
                .role(original.getRole())
                .content(content)
                .metadata(metadata)
                .timestamp(original.getTimestamp())
                .usage(original.getUsage())
                .build();
        rewriter.accept(rewritten);
    }

    // ———— 跨轮池回读（§2.2：历史 Msg metadata；被 Compaction 清理则自然缺席 → 降级 unverified） ————

    private List<Map<String, Object>> historyPool() {
        List<Map<String, Object>> entries = new ArrayList<>();
        if (runtimeContext == null || runtimeContext.getAgentState() == null) {
            return entries;
        }
        List<Msg> context = runtimeContext.getAgentState().getContext();
        // 逆序回看：最近的 assistant Msg 优先；本回合末轮 Msg 尚未入 state（hook 后写入），无自读问题
        for (int i = context.size() - 1; i >= 0 && entries.size() < TrustTurnProcessor.POOL_SUMMARY_LIMIT;
                i--) {
            Object value = context.get(i).getMetadata() == null
                    ? null : context.get(i).getMetadata().get(POOL_METADATA_KEY);
            if (!(value instanceof List<?> list)) {
                continue;
            }
            for (Object element : list) {
                if (element instanceof Map<?, ?> map) {
                    entries.add(asStringMap(map));
                }
            }
        }
        return entries;
    }

    private static Map<String, Object> asStringMap(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return out;
    }

    /** 近期 user 文本：PRE_CALL 捕获 + state 上下文 user 消息（去重、最近优先、上限回看）。 */
    private List<String> recentUserTexts() {
        List<String> texts = new ArrayList<>();
        if (runtimeContext != null && runtimeContext.getAgentState() != null) {
            List<Msg> context = runtimeContext.getAgentState().getContext();
            for (int i = context.size() - 1; i >= 0 && texts.size() < RECENT_USER_TEXTS_LIMIT; i--) {
                Msg msg = context.get(i);
                if (msg.getRole() == MsgRole.USER
                        && msg.getTextContent() != null && !msg.getTextContent().isBlank()) {
                    texts.add(msg.getTextContent());
                }
            }
        }
        synchronized (turnUserTexts) {
            for (String text : turnUserTexts) {
                if (!texts.contains(text) && texts.size() < RECENT_USER_TEXTS_LIMIT) {
                    texts.add(text);
                }
            }
        }
        return texts;
    }

    /**
     * Custom 事件携带的 messageId（§4.3「AG-UI 线上 messageId 同源」）：优先取
     * {@link TrustWireMessageIdMiddleware} 观察到的线上 replyId（TEXT_MESSAGE messageId），
     * 观察缺席（如装配未挂中间件/无文本块事件）时回退 assistant Msg id。
     */
    private String messageId(Msg original) {
        if (runtimeContext != null) {
            Object wire = runtimeContext.get(TrustWireMessageIdMiddleware.RC_KEY);
            if (wire instanceof String value && !value.isBlank()) {
                return value;
            }
        }
        return original == null ? "" : original.getId();
    }

    // ———— 事件发射（§4.3：correction 先、anchors 后；emitter 缺席静默） ————

    private <T extends HookEvent> Mono<T> emitEvents(String messageId, TrustTurnReport report, T event) {
        List<CustomEvent> events = new ArrayList<>();
        for (CorrectionResult.Correction correction : report.corrections()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("messageId", messageId);
            value.put("snippet", correction.snippet());
            value.put("occ", correction.occ());
            value.put("replacement", correction.replacement());
            value.put("note", correction.note());
            events.add(new CustomEvent(CORRECTION_EVENT, value));
        }
        Map<String, Object> anchorsValue = new LinkedHashMap<>();
        anchorsValue.put("messageId", messageId);
        anchorsValue.put("payload", report.toPayload());
        events.add(new CustomEvent(ANCHORS_EVENT, anchorsValue));
        return Mono.deferContextual(ctx -> {
            AgentEventEmitter.fromContext(ctx).ifPresent(emitter -> events.forEach(emitter::emit));
            return Mono.just(event);
        });
    }

    // ———— 留痕（MS-30 看板供数：字段名稳定） ————

    private void logTurn(String anchor, String messageId, TrustTurnReport report) {
        log.info("trust.turn anchor={} messageId={} verified={} sourced={} unverified={} "
                        + "exempted={} corrections={} correctionFailures={} anchors={} poolSize={} rewritten={}",
                anchor,
                messageId,
                report.stats().verified(),
                report.stats().sourced(),
                report.stats().unverified(),
                report.exempted(),
                report.corrections().size(),
                report.correctionFailures(),
                report.anchors().size(),
                report.poolSummary().size(),
                report.rewritten());
    }
}
