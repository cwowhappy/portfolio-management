package com.portfolio.invest.agent.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.agent.trust.RecordingAgentToolDecorator;
import com.portfolio.invest.agent.trust.TrustAgentHook;
import com.portfolio.invest.agent.trust.TrustContext;
import com.portfolio.invest.agent.trust.TrustWireMessageIdMiddleware;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.observability.ObservabilityRecorder;
import com.portfolio.invest.domain.observability.ToolCallObservation;
import com.portfolio.invest.domain.observability.TurnObservation;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.ErrorEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PostSummaryEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

/**
 * ObservabilityAgentHook 单元测试（MS-30 B6，设计规格 §5.2）：轮级采集——usage 逐轮累计
 * （每次模型调用一个 Msg，POST_REASONING/POST_SUMMARY 逐份累加）、PRE_CALL→POST_CALL 时延、
 * trust 并集（同回合 trust 先行处理产物，identity 护栏防跨轮陈旧归因）、标识符
 * （rc sessionId/userId + 线上 messageId 优先）、ErrorEvent failed 轮与降级出口
 * （recorder 失败事件照常传播）。真实 agentscope 事件对象 + mock recorder；
 * 时延断言用真墙钟（沿 RecordingAgentToolDecoratorDurationTest 先例，固定 Clock 只钉 createdAt）。
 */
class ObservabilityAgentHookTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant FIXED_NOW = Instant.parse("2026-10-10T01:02:03Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    private static final String POOL_JSON = "{\"price\":1520.33,\"time\":\"2026-10-05 14:59:32\"}";
    /** 计时断言阈值（睡 60ms 断 ≥50ms，留抖动余量——Task 8 DurationTest 同款）。 */
    private static final long SLEEP_MS = 60;
    private static final long ASSERT_AT_LEAST_MS = 50;

    private final ObservabilityRecorder recorder = mock(ObservabilityRecorder.class);
    private final TrustAgentHook trustHook = new TrustAgentHook(new InvestProperties().getTrust());

    /** 事件构造要求的非空 Agent 替身（hook 不解引用其行为面，全方法桩实现——TrustAgentHookTest 同款）。 */
    static final Agent STUB_AGENT = new Agent() {
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
        public Mono<Msg> call(List<Msg> messages) {
            return Mono.empty();
        }

        @Override
        public Mono<Msg> call(List<Msg> messages, Class<?> outputFormat) {
            return Mono.empty();
        }

        @Override
        public Mono<Msg> call(List<Msg> messages, com.fasterxml.jackson.databind.JsonNode outputFormat) {
            return Mono.empty();
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
        public Mono<Void> observe(Msg message) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> observe(List<Msg> messages) {
            return Mono.empty();
        }
    };

    @AfterEach
    void cleanThreadLocalFallback() {
        TrustContext.reset();
    }

    // ———— 脚手架 ————

    private static RuntimeContext bound() {
        return RuntimeContext.builder().userId("7").sessionId("conv-9").build();
    }

    private ObservabilityAgentHook hook(RuntimeContext rc) {
        ObservabilityAgentHook hook =
                new ObservabilityAgentHook(recorder, trustHook, MAPPER, FIXED_CLOCK);
        hook.setRuntimeContext(rc);
        return hook;
    }

    private static Msg msg(String id, ChatUsage usage) {
        Msg.Builder builder = Msg.builder().id(id).role(MsgRole.ASSISTANT).textContent("回复正文");
        if (usage != null) {
            builder.usage(usage);
        }
        return builder.build();
    }

    /** 经真实 decorator 播种一条当轮工具实录（TrustContext.record 包私有，观测只读）。
     * 失败路径 decorator 照常重抛（旁路不改调用语义），吞掉只取入池观测。 */
    private static void seedToolCall(RuntimeContext rc, String toolName, boolean mcp,
            Mono<ToolResultBlock> behavior, Map<String, Object> input) {
        RecordingAgentToolDecorator decorator =
                new RecordingAgentToolDecorator(new StubTool(toolName, p -> behavior), mcp, MAPPER);
        ToolUseBlock use = new ToolUseBlock("call_" + toolName, toolName, input, "{}", Map.of());
        try {
            decorator.callAsync(ToolCallParam.builder()
                    .toolUseBlock(use).input(input).runtimeContext(rc).build()).block();
        } catch (Exception expected) {
            // 失败种子：doOnError 已记录观测后重抛——吞掉只留入池结果
        }
    }

    private TurnObservation capturedTurn() {
        ArgumentCaptor<TurnObservation> captor = ArgumentCaptor.forClass(TurnObservation.class);
        verify(recorder).recordTurn(captor.capture(), anyList());
        return captor.getValue();
    }

    // ———— usage 累计（§5.2：每次模型调用一个 Msg，回合 token = 逐轮累加） ————

    @DisplayName("usage 逐轮累计：两轮 POST_REASONING 各 (100,50)/(30,20) → 130/70/200")
    @Test
    void givenUsageAcrossReasoningRounds_whenPostCall_thenTokensAccumulated() {
        ObservabilityAgentHook hook = hook(bound());
        hook.onEvent(new PreCallEvent(STUB_AGENT, List.of())).block();
        hook.onEvent(new PostReasoningEvent(STUB_AGENT, "model", null, msg("r1", new ChatUsage(100, 50, 0.01)))).block();
        hook.onEvent(new PostReasoningEvent(STUB_AGENT, "model", null, msg("r2", new ChatUsage(30, 20, 0.01)))).block();
        hook.onEvent(new PostCallEvent(STUB_AGENT, msg("m-1", new ChatUsage(100, 50, 0.01)))).block();

        TurnObservation turn = capturedTurn();
        assertThat(turn.promptTokens()).isEqualTo(130);
        assertThat(turn.completionTokens()).isEqualTo(70);
        assertThat(turn.totalTokens()).isEqualTo(200);
        assertThat(turn.failed()).isFalse();
        assertThat(turn.toolCount()).isZero();
    }

    @DisplayName("POST_SUMMARY 的 usage 同口径累计（maxIters 收尾也是一次模型调用）")
    @Test
    void givenSummaryUsage_whenPostSummary_thenAccumulated() {
        ObservabilityAgentHook hook = hook(bound());
        hook.onEvent(new PreCallEvent(STUB_AGENT, List.of())).block();
        hook.onEvent(new PostSummaryEvent(STUB_AGENT, "model", null, msg("s1", new ChatUsage(10, 5, 0.01)))).block();
        hook.onEvent(new PostCallEvent(STUB_AGENT, msg("m-1", null))).block();

        TurnObservation turn = capturedTurn();
        assertThat(turn.promptTokens()).isEqualTo(10);
        assertThat(turn.completionTokens()).isEqualTo(5);
        assertThat(turn.totalTokens()).isEqualTo(15);
    }

    @DisplayName("全程无 usage（模型缺报）：token 三分量为 null（读端口口径：缺失不进插值）")
    @Test
    void givenUsageMissingAllRounds_whenPostCall_thenTokenFieldsNull() {
        ObservabilityAgentHook hook = hook(bound());
        hook.onEvent(new PreCallEvent(STUB_AGENT, List.of())).block();
        hook.onEvent(new PostReasoningEvent(STUB_AGENT, "model", null, msg("r1", null))).block();
        hook.onEvent(new PostCallEvent(STUB_AGENT, msg("m-1", null))).block();

        TurnObservation turn = capturedTurn();
        assertThat(turn.promptTokens()).isNull();
        assertThat(turn.completionTokens()).isNull();
        assertThat(turn.totalTokens()).isNull();
    }

    // ———— 时延（PRE_CALL 记 nanoTime 起点 → POST_CALL 折毫秒） ————

    @DisplayName("时延：真墙钟计时（睡 ≥50ms）且 createdAt 由固定 Clock 钉死")
    @Test
    void givenSleepBetweenPreAndPostCall_whenPostCall_thenDurationAndCreatedAt() throws InterruptedException {
        ObservabilityAgentHook hook = hook(bound());
        hook.onEvent(new PreCallEvent(STUB_AGENT, List.of())).block();
        Thread.sleep(SLEEP_MS);
        hook.onEvent(new PostCallEvent(STUB_AGENT, msg("m-1", null))).block();

        TurnObservation turn = capturedTurn();
        assertThat(turn.durationMs())
                .as("计时含 PRE_CALL→POST_CALL 全程，须覆盖睡眠时长")
                .isBetween(ASSERT_AT_LEAST_MS, 60_000L);
        assertThat(turn.createdAt()).isEqualTo(FIXED_NOW);
    }

    // ———— 降级出口（Review Focus #3：观测自身失效不断对话） ————

    @DisplayName("recorder 抛异常：POST_CALL 处理不外抛，事件原样继续传播")
    @Test
    void givenRecorderThrows_whenPostCall_thenEventPropagatesUnharmed() {
        doThrow(new IllegalStateException("db down")).when(recorder).recordTurn(any(), anyList());
        ObservabilityAgentHook hook = hook(bound());
        hook.onEvent(new PreCallEvent(STUB_AGENT, List.of())).block();
        PostCallEvent event = new PostCallEvent(STUB_AGENT, msg("m-1", null));

        assertThatCode(() -> {
            PostCallEvent propagated = hook.onEvent(event).block();
            assertThat(propagated).isSameAs(event);
        }).doesNotThrowAnyException();
    }

    // ———— failed 轮（ErrorEvent：库内错误路不走 POST_CALL，failed 轮唯一收尾锚点） ————

    @DisplayName("ErrorEvent：failed=true + errorSummary（类名+消息，超限截断），token 照记")
    @Test
    void givenErrorEvent_whenOnEvent_thenFailedTurnRecordedWithTruncatedSummary() {
        ObservabilityAgentHook hook = hook(bound());
        hook.onEvent(new PreCallEvent(STUB_AGENT, List.of())).block();
        hook.onEvent(new PostReasoningEvent(STUB_AGENT, "model", null, msg("r1", new ChatUsage(10, 5, 0.01)))).block();
        hook.onEvent(new ErrorEvent(STUB_AGENT,
                new IllegalStateException("x".repeat(300)))).block();

        TurnObservation turn = capturedTurn();
        assertThat(turn.failed()).isTrue();
        assertThat(turn.errorSummary())
                .startsWith("IllegalStateException:")
                .hasSizeLessThan(300);
        assertThat(turn.errorSummary().length())
                .isLessThanOrEqualTo(ObservabilityAgentHook.ERROR_SUMMARY_MAX_CHARS + 1);
        assertThat(turn.promptTokens()).isEqualTo(10);
        assertThat(turn.completionTokens()).isEqualTo(5);
        assertThat(turn.durationMs()).isNotNull();
    }

    @DisplayName("防双写：ErrorEvent 已记录后迟到 POST_CALL 不再落第二条")
    @Test
    void givenErrorThenLatePostCall_whenBothFired_thenRecordedOnce() {
        ObservabilityAgentHook hook = hook(bound());
        hook.onEvent(new PreCallEvent(STUB_AGENT, List.of())).block();
        hook.onEvent(new ErrorEvent(STUB_AGENT, new IllegalStateException("boom"))).block();
        hook.onEvent(new PostCallEvent(STUB_AGENT, msg("m-1", null))).block();

        verify(recorder, times(1)).recordTurn(any(), anyList());
    }

    // ———— trust 并集（同回合 trust 先行产物，identity 护栏防跨轮陈旧归因） ————

    @DisplayName("trust 并集：trust 本轮已处理（verified=1）→ trustStats 六键标量并入轮观测")
    @Test
    void givenTrustProcessedThisTurn_whenPostCall_thenTrustStatsMerged() {
        RuntimeContext rc = bound();
        ObservabilityAgentHook hook = hook(rc);
        hook.onEvent(new PreCallEvent(STUB_AGENT, List.of())).block();
        // trust 先行处理本回合（真实流水：fresh 池 → 播种真值 → 末轮锚定）
        trustHook.setRuntimeContext(rc);
        trustHook.onEvent(new PreCallEvent(STUB_AGENT, List.of())).block();
        seedToolCall(rc, "get_quote", false, Mono.just(ToolResultBlock.text(POOL_JSON)),
                Map.of("code", "600519"));
        trustHook.onEvent(new PostReasoningEvent(STUB_AGENT, "model", null,
                Msg.builder().id("m-t").role(MsgRole.ASSISTANT).textContent("现价1520.33元。").build())).block();

        hook.onEvent(new PostCallEvent(STUB_AGENT, msg("m-1", null))).block();

        TurnObservation turn = capturedTurn();
        assertThat(turn.trustStats()).isNotNull();
        assertThat(turn.trustStats()).containsKeys(
                "verified", "sourced", "unverified", "corrections", "correctionFailures", "exempted");
        assertThat(turn.trustStats().get("verified")).isEqualTo(1);
        // Task 8 传导约束：值域保持标量（JSONB 序列化失败防护）
        assertThat(turn.trustStats().values()).allSatisfy(v -> assertThat(v).isInstanceOf(Number.class));
        assertThat(turn.toolCount()).isEqualTo(1);
    }

    @DisplayName("identity 护栏：下一轮 trust 未处理（lastReport 未换新）→ trustStats=null 不陈旧归因")
    @Test
    void givenTrustNotProcessedNextTurn_whenPostCall_thenTrustStatsNull() {
        RuntimeContext rc = bound();
        ObservabilityAgentHook hook = hook(rc);
        trustHook.setRuntimeContext(rc);
        // 回合一：trust 处理过（lastReport 落定）
        hook.onEvent(new PreCallEvent(STUB_AGENT, List.of())).block();
        trustHook.onEvent(new PreCallEvent(STUB_AGENT, List.of())).block();
        trustHook.onEvent(new PostReasoningEvent(STUB_AGENT, "model", null,
                Msg.builder().id("m-t").role(MsgRole.ASSISTANT).textContent("今日大盘上涨。").build())).block();
        // 回合二：error 轮，trust 无末轮处理 → 不应归因回合一的 report
        hook.onEvent(new PreCallEvent(STUB_AGENT, List.of())).block();
        hook.onEvent(new ErrorEvent(STUB_AGENT, new IllegalStateException("mid-turn boom"))).block();

        TurnObservation turn = capturedTurn();
        assertThat(turn.failed()).isTrue();
        assertThat(turn.trustStats()).isNull();
    }

    // ———— 标识符（rc sessionId/userId + 线上 messageId 优先） ————

    @DisplayName("标识符：userId=7、conversationId=sessionId、messageId 优先 RC_KEY 线上 id")
    @Test
    void givenIdentifiersInRuntimeContext_whenPostCall_thenTakenFromContext() {
        RuntimeContext rc = bound();
        rc.put(TrustWireMessageIdMiddleware.RC_KEY, "wire-1");
        ObservabilityAgentHook hook = hook(rc);
        hook.onEvent(new PreCallEvent(STUB_AGENT, List.of())).block();
        hook.onEvent(new PostCallEvent(STUB_AGENT, msg("msg-77", null))).block();

        TurnObservation turn = capturedTurn();
        assertThat(turn.userId()).isEqualTo(7L);
        assertThat(turn.conversationId()).isEqualTo("conv-9");
        assertThat(turn.messageId()).isEqualTo("wire-1");
    }

    @DisplayName("messageId 回退：rc 无线上 id 时取 final Msg id；userId 非数字回退 null")
    @Test
    void givenNoWireIdAndUnparsableUserId_whenPostCall_thenFallbacks() {
        RuntimeContext rc = RuntimeContext.builder().userId("abc").sessionId("conv-9").build();
        ObservabilityAgentHook hook = hook(rc);
        hook.onEvent(new PreCallEvent(STUB_AGENT, List.of())).block();
        hook.onEvent(new PostCallEvent(STUB_AGENT, msg("msg-77", null))).block();

        TurnObservation turn = capturedTurn();
        assertThat(turn.messageId()).isEqualTo("msg-77");
        assertThat(turn.userId()).isNull();
    }

    // ———— 工具观测映射（数据源 = TrustContext 当轮实录，历史池 metadata 不进观测） ————

    @DisplayName("工具实录映射：builtin DATA 成功 + MCP CALL 失败 → 两行观测全字段")
    @Test
    void givenToolInvocationsInTrustContext_whenPostCall_thenMappedObservations() throws Exception {
        RuntimeContext rc = bound();
        ObservabilityAgentHook hook = hook(rc);
        hook.onEvent(new PreCallEvent(STUB_AGENT, List.of())).block();
        seedToolCall(rc, "get_quote", false,
                Mono.just(ToolResultBlock.text(POOL_JSON)), Map.of("code", "600519"));
        seedToolCall(rc, "mcp_search", true,
                Mono.error(new IllegalStateException("下游超时")), Map.of("q", "茅台"));
        hook.onEvent(new PostCallEvent(STUB_AGENT, msg("m-1", null))).block();

        ArgumentCaptor<TurnObservation> turnCaptor = ArgumentCaptor.forClass(TurnObservation.class);
        ArgumentCaptor<List<ToolCallObservation>> toolsCaptor = ArgumentCaptor.captor();
        verify(recorder).recordTurn(turnCaptor.capture(), toolsCaptor.capture());
        assertThat(turnCaptor.getValue().toolCount()).isEqualTo(2);

        List<ToolCallObservation> tools = toolsCaptor.getValue();
        ToolCallObservation quote = tools.get(0);
        assertThat(quote.toolName()).isEqualTo("get_quote");
        assertThat(MAPPER.readTree(quote.argsJson()).path("code").asText()).isEqualTo("600519");
        assertThat(quote.resultText()).isEqualTo(POOL_JSON);
        assertThat(quote.specCount()).isZero();
        assertThat(quote.asOf()).isEqualTo("2026-10-05 14:59:32");
        assertThat(quote.asOfKind()).isEqualTo("data");
        assertThat(quote.mcp()).isFalse();
        assertThat(quote.failed()).isFalse();
        assertThat(quote.durationMs()).isGreaterThanOrEqualTo(0L);
        assertThat(quote.calledAt()).isEqualTo(FIXED_NOW);
        assertThat(quote.messageId()).isEqualTo(turnCaptor.getValue().messageId());

        ToolCallObservation mcp = tools.get(1);
        assertThat(mcp.toolName()).isEqualTo("mcp_search");
        assertThat(mcp.asOfKind()).isEqualTo("call");
        assertThat(mcp.mcp()).isTrue();
        assertThat(mcp.failed()).isTrue();
        assertThat(mcp.resultText()).isEmpty();
        assertThat(mcp.calledAt())
                .as("CALL 线 asOf 即调用时刻，回解真实时间（非回合收尾兜底）")
                .isNotNull();
    }

    // ———— 装配序（priority 钉死 trust 先行、观测后行） ————

    @DisplayName("hook 顺序：反序注册仍按 priority 排出 trust 先行、observability 后行")
    @Test
    void givenBothHooksRegisteredReversed_whenAgentBaseSorts_thenTrustRunsFirst() {
        ObservabilityAgentHook observability = hook(bound());

        assertThat(observability.priority())
                .as("库内 HOOK_COMPARATOR=comparingInt(priority) 升序：大者后行——观测须排在 trust（默认 100）之后")
                .isGreaterThan(trustHook.priority());

        ProbeAgent agent = new ProbeAgent(observability, trustHook); // 故意反序注册
        List<Hook> sorted = agent.getSortedHooks();
        assertThat(sorted).hasSize(2);
        assertThat(sorted.get(0)).isInstanceOf(TrustAgentHook.class);
        assertThat(sorted.get(1)).isInstanceOf(ObservabilityAgentHook.class);
    }

    // ———— 防御路径 ————

    @DisplayName("rc 缺席：PRE_CALL/POST_CALL 照记，标识符与 token 容忍 null（旁路 best-effort）")
    @Test
    void givenNoRuntimeContext_whenPostCall_thenStillRecordedWithNullIdentifiers() {
        ObservabilityAgentHook hook = new ObservabilityAgentHook(recorder, trustHook, MAPPER, FIXED_CLOCK);
        hook.onEvent(new PreCallEvent(STUB_AGENT, List.of())).block();
        hook.onEvent(new PostCallEvent(STUB_AGENT, msg("m-1", null))).block();

        TurnObservation turn = capturedTurn();
        assertThat(turn.userId()).isNull();
        assertThat(turn.conversationId()).isNull();
        assertThat(turn.messageId()).isEqualTo("m-1");
        assertThat(turn.durationMs()).isNotNull();
        assertThat(turn.toolCount()).isZero();
    }

    // ———— 替身 ————

    /** 可编程 stub 工具（RecordingAgentToolDecoratorDurationTest 同款）：behavior 决定 callAsync 行为。 */
    static class StubTool extends ToolBase {
        private final java.util.function.Function<ToolCallParam, Mono<ToolResultBlock>> behavior;

        StubTool(String name, java.util.function.Function<ToolCallParam, Mono<ToolResultBlock>> behavior) {
            super(ToolBase.builder()
                    .name(name)
                    .description("观测播种用 stub 工具")
                    .inputSchema(Map.of("type", "object"))
                    .readOnly(true)
                    .concurrencySafe(true));
            this.behavior = behavior;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return behavior.apply(param);
        }
    }

    /** 装配序探针：真实 AgentBase 排序面（addHook 即 sortHooks 按 priority 升序）。 */
    static final class ProbeAgent extends io.agentscope.core.agent.AgentBase {
        ProbeAgent(Hook... hooks) {
            super("ordering-probe");
            for (Hook h : hooks) {
                addHook(h);
            }
        }

        @Override
        protected Mono<Msg> doCall(List<Msg> messages) {
            return Mono.empty();
        }

        @Override
        protected Mono<Msg> handleInterrupt(InterruptContext context, Msg... messages) {
            return Mono.empty();
        }
    }
}
