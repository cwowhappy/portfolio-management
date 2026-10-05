package com.portfolio.invest.agent.trust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.portfolio.invest.config.InvestProperties;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PostSummaryEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.state.AgentState;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TrustAgentHook 单元测试（MS-29 B5）：事件路由与回合边界（真实 agentscope 事件对象、null agent——
 * hook 不解引用 agent）。锚点语义（B0/B5 探针实证）：POST_REASONING 末轮为主（改写落 state+返回）、
 * POST_SUMMARY 为辅（maxIters 耗尽路径）、POST_CALL 兜底（改写仅达返回值）+ 回合收尾 reset。
 */
class TrustAgentHookTest {

    private static final String WRONG = "现价15.20元";
    private static final String POOL_JSON = "{\"price\":1520.33,\"time\":\"2026-10-05 14:59:32\"}";

    private final TrustAgentHook hook = new TrustAgentHook(new InvestProperties().getTrust());

    /** 事件构造要求的非空 Agent 替身（hook 不解引用其行为面，全方法桩实现）。 */
    static final io.agentscope.core.agent.Agent STUB_AGENT = new io.agentscope.core.agent.Agent() {
        @Override
        public String getAgentId() {
            return "stub";
        }

        @Override
        public String getName() {
            return "stub";
        }

        @Override
        public void interrupt() {
        }

        @Override
        public void interrupt(Msg msg) {
        }

        @Override
        public reactor.core.publisher.Mono<Msg> call(List<Msg> messages) {
            return reactor.core.publisher.Mono.empty();
        }

        @Override
        public reactor.core.publisher.Mono<Msg> call(List<Msg> messages, Class<?> outputFormat) {
            return reactor.core.publisher.Mono.empty();
        }

        @Override
        public reactor.core.publisher.Mono<Msg> call(List<Msg> messages,
                com.fasterxml.jackson.databind.JsonNode outputFormat) {
            return reactor.core.publisher.Mono.empty();
        }

        @Override
        public reactor.core.publisher.Flux<io.agentscope.core.agent.Event> stream(
                List<Msg> messages, io.agentscope.core.agent.StreamOptions options) {
            return reactor.core.publisher.Flux.empty();
        }

        @Override
        public reactor.core.publisher.Flux<io.agentscope.core.agent.Event> stream(
                List<Msg> messages, io.agentscope.core.agent.StreamOptions options, Class<?> outputFormat) {
            return reactor.core.publisher.Flux.empty();
        }

        @Override
        public reactor.core.publisher.Flux<io.agentscope.core.agent.Event> stream(
                List<Msg> messages, io.agentscope.core.agent.StreamOptions options,
                com.fasterxml.jackson.databind.JsonNode outputFormat) {
            return reactor.core.publisher.Flux.empty();
        }

        @Override
        public reactor.core.publisher.Mono<Void> observe(Msg message) {
            return reactor.core.publisher.Mono.empty();
        }

        @Override
        public reactor.core.publisher.Mono<Void> observe(List<Msg> messages) {
            return reactor.core.publisher.Mono.empty();
        }
    };

    private RuntimeContext bound() {
        RuntimeContext rc = RuntimeContext.builder().userId("1").sessionId("s1").build();
        hook.setRuntimeContext(rc);
        return rc;
    }

    private static Msg assistantMsg(String text) {
        return Msg.builder()
                .id("m-1")
                .name("invest")
                .role(MsgRole.ASSISTANT)
                .textContent(text)
                .usage(new ChatUsage(10, 5, 0.01))
                .build();
    }

    private static void recordQuoteTruth(RuntimeContext rc) {
        TrustContext.current(rc).record(new ToolInvocation(
                "get_quote", Map.of("code", "600519"), POOL_JSON,
                List.of(), "2026-10-05 14:59:32", ToolInvocation.AsOfKind.DATA, false));
    }

    @DisplayName("POST_REASONING 末轮：改写消息（修正文本+池摘要 metadata），保留 id/name/role/usage")
    @Test
    void givenFinalRoundReasoning_whenOnEvent_thenReasoningMessageRewritten() {
        RuntimeContext rc = bound();
        recordQuoteTruth(rc);
        Msg original = assistantMsg(WRONG);
        PostReasoningEvent event = new PostReasoningEvent(STUB_AGENT, "model", null, original);

        hook.onEvent(event).block();

        Msg rewritten = event.getReasoningMessage();
        assertThat(rewritten).isNotSameAs(original);
        assertThat(rewritten.getTextContent())
                .contains("1520.33元")
                .contains("> ⚠ 校验修正：原文误述 15.20元");
        assertThat(rewritten.getId()).isEqualTo("m-1");
        assertThat(rewritten.getName()).isEqualTo("invest");
        assertThat(rewritten.getRole()).isEqualTo(MsgRole.ASSISTANT);
        assertThat(rewritten.getUsage()).isEqualTo(original.getUsage());
        assertThat(rewritten.getMetadata()).containsKey(TrustAgentHook.POOL_METADATA_KEY);
    }

    @DisplayName("POST_REASONING 工具轮（含 ToolUseBlock）：不改写")
    @Test
    void givenToolRoundReasoning_whenOnEvent_thenNotRewritten() {
        bound();
        Msg original = Msg.builder().role(MsgRole.ASSISTANT)
                .content(new ToolUseBlock("c1", "get_quote", Map.of(), "{}", Map.of()))
                .textContent("").build();
        PostReasoningEvent event = new PostReasoningEvent(STUB_AGENT, "model", null, original);

        hook.onEvent(event).block();

        assertThat(event.getReasoningMessage()).isSameAs(original);
    }

    @DisplayName("改写保留非文本块：thinking 块原样随行（reasoner 思考内容不丢失）")
    @Test
    void givenThinkingBlockInFinalMsg_whenRewritten_thenNonTextBlocksPreserved() {
        RuntimeContext rc = bound();
        recordQuoteTruth(rc);
        io.agentscope.core.message.ThinkingBlock thinking =
                io.agentscope.core.message.ThinkingBlock.builder().thinking("先查价").build();
        Msg original = Msg.builder().id("m-1").role(MsgRole.ASSISTANT)
                .content(List.of(thinking, io.agentscope.core.message.TextBlock.builder()
                        .text(WRONG).build()))
                .build();
        PostReasoningEvent event = new PostReasoningEvent(STUB_AGENT, "model", null, original);

        hook.onEvent(event).block();

        Msg rewritten = event.getReasoningMessage();
        assertThat(rewritten.getContentBlocks(io.agentscope.core.message.ThinkingBlock.class))
                .hasSize(1);
        assertThat(rewritten.getTextContent()).contains("1520.33元");
    }

    @DisplayName("幂等防重：同回合 POST_REASONING 已处理后 POST_CALL 不再改写")
    @Test
    void givenAlreadyProcessedTurn_whenPostCall_thenNoRewrite() {
        RuntimeContext rc = bound();
        recordQuoteTruth(rc);
        hook.onEvent(new PostReasoningEvent(STUB_AGENT, "model", null, assistantMsg(WRONG))).block();

        Msg finalMsg = assistantMsg(WRONG);
        PostCallEvent call = new PostCallEvent(STUB_AGENT, finalMsg);
        hook.onEvent(call).block();

        assertThat(call.getFinalMessage()).isSameAs(finalMsg);
    }

    @DisplayName("POST_SUMMARY：setSummaryMessage 改写生效")
    @Test
    void givenSummaryEvent_whenOnEvent_thenSummaryMessageRewritten() {
        RuntimeContext rc = bound();
        recordQuoteTruth(rc);
        PostSummaryEvent event = new PostSummaryEvent(STUB_AGENT, "model", null, assistantMsg(WRONG));

        hook.onEvent(event).block();

        assertThat(event.getSummaryMessage().getTextContent()).contains("1520.33元");
    }

    @DisplayName("POST_CALL 兜底：未处理过的纯文本末轮 → setFinalMessage 改写（返回值半边）")
    @Test
    void givenUnprocessedTurn_whenPostCall_thenFinalMessageRewritten() {
        RuntimeContext rc = bound();
        recordQuoteTruth(rc);

        PostCallEvent event = new PostCallEvent(STUB_AGENT, assistantMsg(WRONG));
        hook.onEvent(event).block();

        assertThat(event.getFinalMessage().getTextContent()).contains("1520.33元");
    }

    @DisplayName("回合边界：PRE_CALL 重置已处理标记与真值池，user 输入进入豁免文本")
    @Test
    void givenPreCall_whenOnEvent_thenPoolResetAndUserTextsCaptured() {
        RuntimeContext rc = bound();
        recordQuoteTruth(rc); // 上一回合残留
        hook.onEvent(new PostReasoningEvent(STUB_AGENT, "model", null, assistantMsg(WRONG))).block();

        hook.onEvent(new PreCallEvent(STUB_AGENT, List.of(
                assistantMsg("历史"), // assistant 不入豁免
                Msg.builder().role(MsgRole.USER).textContent("我的成本价是1800.5元").build())))
                .block();

        // 池已重置：本回合无工具真值，1800.5 为用户来源 → 豁免不锚定
        PostReasoningEvent event =
                new PostReasoningEvent(STUB_AGENT, "model", null, assistantMsg("成本1800.5元。"));
        hook.onEvent(event).block();

        assertThat(event.getReasoningMessage().getTextContent()).contains("1800.5元");
        assertThat(hook.lastReport().exempted()).isEqualTo(1);
    }

    @DisplayName("advice 接线（B6）：标记行剥离进改写 Msg，payload.advice 进 trust.anchors 事件")
    @Test
    void givenAdviceMarkerAndLexiconWord_whenOnEvent_thenMarkerStrippedAndAdviceInPayload() {
        bound();
        List<CustomEvent> seen = new java.util.ArrayList<>();
        PostReasoningEvent event = new PostReasoningEvent(STUB_AGENT, "model", null,
                assistantMsg("估值偏低，建议分批建仓。\n<!--advice-->"));

        hook.onEvent(event)
                .contextWrite(ctx -> ctx.put(io.agentscope.core.event.AgentEventEmitter.CONTEXT_KEY,
                        (io.agentscope.core.event.AgentEventEmitter) e -> {
                            if (e instanceof CustomEvent c) {
                                seen.add(c);
                            }
                        }))
                .block();

        // 改写 Msg：标记行剥离（rewritten() 触发 applyRewrite），干净文本成为最终消息
        assertThat(event.getReasoningMessage().getTextContent()).isEqualTo("估值偏低，建议分批建仓。");
        // trust.anchors 事件 payload：advice {flag, by=both, text}
        assertThat(seen).extracting(CustomEvent::getName)
                .containsExactly(TrustAgentHook.ANCHORS_EVENT);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) seen.get(0).getValue().get("payload");
        assertThat(payload).containsKey("advice");
        @SuppressWarnings("unchecked")
        Map<String, Object> advice = (Map<String, Object>) payload.get("advice");
        assertThat(advice).containsEntry("flag", true).containsEntry("by", "both");
    }

    @DisplayName("跨轮池回读：rc state 内历史 Msg metadata 参与本回合配对")
    @Test
    void givenHistoryMetadataInState_whenOnEvent_thenHistoryPoolPaired() {
        RuntimeContext rc = bound();
        Msg prior = Msg.builder().role(MsgRole.ASSISTANT).textContent("现价1520.33元")
                .metadata(Map.of(TrustAgentHook.POOL_METADATA_KEY, List.of(
                        Map.of("v", "1520.33", "tool", "get_quote",
                                "asOf", "2026-10-05 14:59:32", "kind", "data"))))
                .build();
        rc.setAgentState(AgentState.builder().userId("1").sessionId("s1").addMessage(prior).build());

        PostReasoningEvent event =
                new PostReasoningEvent(STUB_AGENT, "model", null, assistantMsg("再报：现价1520.33元。"));
        hook.onEvent(event).block();

        assertThat(hook.lastReport().anchors()).hasSize(1);
        assertThat(hook.lastReport().anchors().get(0).state()).isEqualTo(TrustVerdict.VERIFIED);
        assertThat(hook.lastReport().anchors().get(0).asOf())
                .isEqualTo("2026-10-05 14:59:32");
    }

    @DisplayName("护栏：处理异常 → 原文直通不抛错不中断")
    @Test
    void givenProcessingError_whenOnEvent_thenPassthroughWithoutThrowing() {
        bound();
        TrustAgentHook failing = new TrustAgentHook(new ThrowingProcessor());
        Msg original = assistantMsg(WRONG);
        PostReasoningEvent event = new PostReasoningEvent(STUB_AGENT, "model", null, original);

        assertThatCode(() -> failing.onEvent(event).block()).doesNotThrowAnyException();

        assertThat(event.getReasoningMessage()).isSameAs(original);
    }

    @DisplayName("Custom 事件发射：流内出现 trust.correction（先）与 trust.anchors（后）")
    @Test
    void givenCorrectionHappened_whenOnEvent_thenCustomEventsEmittedInOrder() throws Exception {
        RuntimeContext rc = bound();
        recordQuoteTruth(rc);
        PostReasoningEvent event = new PostReasoningEvent(STUB_AGENT, "model", null, assistantMsg(WRONG));

        List<CustomEvent> seen = new java.util.ArrayList<>();
        hook.onEvent(event)
                .contextWrite(ctx -> ctx.put(io.agentscope.core.event.AgentEventEmitter.CONTEXT_KEY,
                        (io.agentscope.core.event.AgentEventEmitter) e -> {
                            if (e instanceof CustomEvent c) {
                                seen.add(c);
                            }
                        }))
                .block();

        assertThat(seen).extracting(CustomEvent::getName)
                .containsExactly(TrustAgentHook.CORRECTION_EVENT, TrustAgentHook.ANCHORS_EVENT);
        CustomEvent anchors = seen.get(1);
        assertThat(anchors.getValue()).containsEntry("messageId", "m-1");
        assertThat(anchors.getValue()).containsKey("payload");
    }

    @DisplayName("无修正时只发 trust.anchors 一条")
    @Test
    void givenNoCorrection_whenOnEvent_thenOnlyAnchorsEmitted() {
        RuntimeContext rc = bound();
        recordQuoteTruth(rc);
        List<CustomEvent> seen = new java.util.ArrayList<>();
        PostReasoningEvent event =
                new PostReasoningEvent(STUB_AGENT, "model", null, assistantMsg("现价1520.33元。"));

        hook.onEvent(event)
                .contextWrite(ctx -> ctx.put(io.agentscope.core.event.AgentEventEmitter.CONTEXT_KEY,
                        (io.agentscope.core.event.AgentEventEmitter) e -> {
                            if (e instanceof CustomEvent c) {
                                seen.add(c);
                            }
                        }))
                .block();

        assertThat(seen).extracting(CustomEvent::getName)
                .containsExactly(TrustAgentHook.ANCHORS_EVENT);
    }

    @DisplayName("messageId 取线上 id：rc 有 wireMessageId 时优先于 Msg id（AG-UI 线上同源）")
    @Test
    void givenWireMessageIdInRc_whenOnEvent_thenCustomEventsCarryWireId() {
        RuntimeContext rc = bound();
        rc.put(TrustWireMessageIdMiddleware.RC_KEY, "wire-id-1");
        recordQuoteTruth(rc);
        List<CustomEvent> seen = new java.util.ArrayList<>();
        PostReasoningEvent event =
                new PostReasoningEvent(STUB_AGENT, "model", null, assistantMsg(WRONG));

        hook.onEvent(event)
                .contextWrite(ctx -> ctx.put(io.agentscope.core.event.AgentEventEmitter.CONTEXT_KEY,
                        (io.agentscope.core.event.AgentEventEmitter) e -> {
                            if (e instanceof CustomEvent c) {
                                seen.add(c);
                            }
                        }))
                .block();

        assertThat(seen).isNotEmpty();
        assertThat(seen).allSatisfy(c ->
                assertThat(c.getValue()).containsEntry("messageId", "wire-id-1"));
    }

    @DisplayName("护栏（fix 轮 1）：emit 在订阅时抛错被单事件吞并，Mono 正常完成且后续事件照发 + WARN 留痕")
    @Test
    void givenThrowingEmitterOnFirstEmit_whenOnEvent_thenSwallowedAndRemainingEmitted() {
        RuntimeContext rc = bound();
        recordQuoteTruth(rc);
        ch.qos.logback.classic.Logger hookLogger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(TrustAgentHook.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        hookLogger.addAppender(appender);
        List<CustomEvent> seen = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        PostReasoningEvent event = new PostReasoningEvent(STUB_AGENT, "model", null, assistantMsg(WRONG));
        try {
            hook.onEvent(event)
                    .contextWrite(ctx -> ctx.put(io.agentscope.core.event.AgentEventEmitter.CONTEXT_KEY,
                            (io.agentscope.core.event.AgentEventEmitter) e -> {
                                if (calls.incrementAndGet() == 1) {
                                    throw new IllegalStateException("sink down");
                                }
                                if (e instanceof CustomEvent c) {
                                    seen.add(c);
                                }
                            }))
                    .block();
        } finally {
            hookLogger.detachAppender(appender);
        }

        // 修正事件（首个 emit）抛错被吞：Mono 正常完成（block 未抛错即证）、anchors 照发、WARN 留痕
        assertThat(seen).extracting(CustomEvent::getName)
                .containsExactly(TrustAgentHook.ANCHORS_EVENT);
        assertThat(appender.list)
                .anyMatch(le -> le.getLevel() == ch.qos.logback.classic.Level.WARN
                        && le.getFormattedMessage().contains("trust.guardrail"));
    }

    /** 护栏用：process 恒抛错的处理器替身。 */
    static final class ThrowingProcessor extends TrustTurnProcessor {
        ThrowingProcessor() {
            super(new InvestProperties().getTrust());
        }

        @Override
        public TrustTurnReport process(String finalText, List<ToolInvocation> currentPool,
                List<Map<String, Object>> historyPool, List<String> recentUserTexts) {
            throw new IllegalStateException("boom");
        }
    }
}
