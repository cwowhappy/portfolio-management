package com.portfolio.invest.agent.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * ObservabilityAgentMiddleware 单元测试（hook-migration，沿 ObservabilityAgentHookTest
 * 14 用例口径迁移）：事件源改 onAgent 层——PRE_CALL→入口重置、POST_REASONING/SUMMARY→
 * ModelCallEndEvent、POST_CALL→AgentResultEvent、ErrorEvent→doOnError。
 * 真值注池沿真实 decorator 播种先例（ObservabilityAgentHookTest:160）。
 *
 * <p>迁移对照：14 用例中 10 例落位于此（trust 双例、标识符双例各自合并为一）；
 * POST_SUMMARY 用例并入 ModelCallEndEvent 单一事件源、原 :419 hook 顺序用例不迁移
 * （priority 装配序退役，次序归 Task 4 factory 测试）、rc 缺席防御独立成例。
 */
class ObservabilityAgentMiddlewareTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-10-12T08:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneId.of("UTC"));
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String POOL_JSON = "{\"price\":1520.33,\"time\":\"2026-10-05 14:59:32\"}";
    /** 计时断言阈值（睡 60ms 断 ≥50ms，留抖动余量——ObservabilityAgentHookTest 同款）。 */
    private static final long SLEEP_MS = 60;
    private static final long ASSERT_AT_LEAST_MS = 50;

    private final ObservabilityRecorder recorder = mock(ObservabilityRecorder.class);
    private final TrustAgentHook trustHook =
            new TrustAgentHook(new InvestProperties().getTrust());

    private ObservabilityAgentMiddleware newMiddleware() {
        return new ObservabilityAgentMiddleware(recorder, trustHook, MAPPER, FIXED_CLOCK);
    }

    private static RuntimeContext ctx(String userId, String sessionId) {
        return RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
    }

    private static Flux<AgentEvent> normalStream(String replyId, ChatUsage usage, Msg result) {
        return Flux.just((AgentEvent) new ModelCallEndEvent(replyId, usage),
                new AgentResultEvent(result));
    }

    /** 事件构造要求的非空 Agent 替身（hook 不解引用其行为面，全方法桩实现——原测同款）。 */
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
    void cleanFallback() {
        TrustContext.reset();
    }

    @Test
    @DisplayName("usage 逐轮累计：两枚 ModelCallEndEvent (100,50)/(30,20) → 130/70/200")
    void givenUsageAcrossModelCalls_whenTurnEnds_thenTokensAccumulated() {
        ObservabilityAgentMiddleware mw = newMiddleware();
        RuntimeContext rc = ctx("7", "conv-1");

        mw.onAgent(null, rc, new AgentInput(List.of()), input -> Flux.just(
                (AgentEvent) new ModelCallEndEvent("r-1", new ChatUsage(100, 50, 0.01)),
                new ModelCallEndEvent("r-2", new ChatUsage(30, 20, 0.01)),
                new AgentResultEvent(msg("m-1")))).blockLast();

        TurnObservation turn = capturedTurn();
        assertThat(turn.promptTokens()).isEqualTo(130);
        assertThat(turn.completionTokens()).isEqualTo(70);
        assertThat(turn.totalTokens()).isEqualTo(200);
        assertThat(turn.failed()).isFalse();
        assertThat(turn.toolCount()).isZero();
    }

    @Test
    @DisplayName("全程无 usage：token 三分量为 null（缺失不进插值口径不变）")
    void givenUsageMissing_whenTurnEnds_thenTokenFieldsNull() {
        ObservabilityAgentMiddleware mw = newMiddleware();
        RuntimeContext rc = ctx("7", "conv-1");

        mw.onAgent(null, rc, new AgentInput(List.of()), input -> Flux.just(
                (AgentEvent) new ModelCallEndEvent("r-1", null),
                new ModelCallEndEvent("r-2", null),
                new AgentResultEvent(msg("m-1")))).blockLast();

        TurnObservation turn = capturedTurn();
        assertThat(turn.promptTokens()).isNull();
        assertThat(turn.completionTokens()).isNull();
        assertThat(turn.totalTokens()).isNull();
    }

    @Test
    @DisplayName("正常轮：AgentResultEvent 落库 failed=false，createdAt=固定 Clock")
    void givenResultEvent_whenTurnEnds_thenRecordedWithFixedClockTimestamp()
            throws InterruptedException {
        ObservabilityAgentMiddleware mw = newMiddleware();
        RuntimeContext rc = ctx("7", "conv-1");
        Flux<AgentEvent> flux = mw.onAgent(null, rc, new AgentInput(List.of()),
                input -> normalStream("r-1", null, msg("m-1")));
        Thread.sleep(SLEEP_MS);
        flux.blockLast();

        TurnObservation turn = capturedTurn();
        assertThat(turn.durationMs())
                .as("计时含入口→收尾全程，须覆盖睡眠时长")
                .isBetween(ASSERT_AT_LEAST_MS, 60_000L);
        assertThat(turn.createdAt()).isEqualTo(FIXED_NOW);
        assertThat(turn.failed()).isFalse();
    }

    @Test
    @DisplayName("recorder 抛异常：正常轮与 failed 轮都不外抛，流原样继续")
    void givenRecorderThrows_whenRecording_thenErrorSwallowedAndStreamUnharmed() {
        doThrow(new IllegalStateException("db down")).when(recorder).recordTurn(any(), any());
        ObservabilityAgentMiddleware mw = newMiddleware();
        RuntimeContext rc = ctx("7", "conv-1");

        // 正常轮：落库异常吞并，流原样走完（原 :249 事件照常传播口径）
        assertThatCode(() -> mw.onAgent(null, rc, new AgentInput(List.of()),
                input -> normalStream("r-1", null, msg("m-1"))).blockLast())
                .doesNotThrowAnyException();

        // failed 轮：doOnError 落库异常同样吞并，blockLast 抛【原始】错误而非观测异常（Review Focus #3）
        assertThatThrownBy(() -> mw.onAgent(null, rc, new AgentInput(List.of()),
                input -> Flux.error(new IllegalStateException("原始错误"))).blockLast())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("原始错误");
    }

    @Test
    @DisplayName("failed 轮：doOnError 落库 failed=true + 错误摘要截断 200 码点")
    void givenDownstreamError_whenTurnFails_thenFailedTurnRecordedWithTruncatedSummary() {
        ObservabilityAgentMiddleware mw = newMiddleware();
        RuntimeContext rc = ctx("7", "conv-1");

        assertThatThrownBy(() -> mw.onAgent(null, rc, new AgentInput(List.of()), input -> Flux.concat(
                Flux.just((AgentEvent) new ModelCallEndEvent("r-1", new ChatUsage(10, 5, 0.01))),
                Flux.error(new IllegalStateException("x".repeat(300))))).blockLast())
                .isInstanceOf(IllegalStateException.class);

        TurnObservation turn = capturedTurn();
        assertThat(turn.failed()).isTrue();
        assertThat(turn.errorSummary())
                .startsWith("IllegalStateException:")
                .hasSizeLessThan(300);
        assertThat(turn.errorSummary().length())
                .isLessThanOrEqualTo(ObservabilityAgentMiddleware.ERROR_SUMMARY_MAX_CHARS + 1);
        assertThat(turn.promptTokens()).isEqualTo(10);
        assertThat(turn.completionTokens()).isEqualTo(5);
        assertThat(turn.durationMs()).isNotNull();
    }

    @Test
    @DisplayName("防双写：AgentResultEvent 已落库后 doOnError 再触发不再落第二条（原始错误照常上抛）")
    void givenErrorThenLateResult_whenBothSeen_thenRecordedOnce() {
        ObservabilityAgentMiddleware mw = newMiddleware();
        RuntimeContext rc = ctx("7", "conv-1");

        assertThatThrownBy(() -> mw.onAgent(null, rc, new AgentInput(List.of()),
                input -> Flux.concat(
                        Flux.just((AgentEvent) new AgentResultEvent(msg("m-1"))),
                        Flux.error(new RuntimeException("boom")))).blockLast())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");

        verify(recorder, times(1)).recordTurn(any(), any());
    }

    @Test
    @DisplayName("trust 并集 + identity 护栏：本轮 trust 已处理并入六键；未处理 → trustStats=null")
    void givenTrustReportStates_whenTurnEnds_thenTrustStatsMergedOrNull() {
        RuntimeContext rc = ctx("7", "conv-1");
        trustHook.setRuntimeContext(rc);
        ObservabilityAgentMiddleware mw = newMiddleware();

        // 回合一：onAgent 入口拍 identity 基线（此刻 trust 未处理）→ 流外先行处理 → 收尾落库并入
        Flux<AgentEvent> turnOne = mw.onAgent(null, rc, new AgentInput(List.of()),
                input -> Flux.just((AgentEvent) new AgentResultEvent(msg("m-1"))));
        // trust 先行处理本回合（真实流水：fresh 池 → 播种真值 → 末轮锚定）
        trustHook.onEvent(new PreCallEvent(STUB_AGENT, List.of())).block();
        seedToolCall(rc, "get_quote", false, Mono.just(ToolResultBlock.text(POOL_JSON)),
                Map.of("code", "600519"));
        trustHook.onEvent(new PostReasoningEvent(STUB_AGENT, "model", null,
                Msg.builder().id("m-t").role(MsgRole.ASSISTANT).textContent("现价1520.33元。").build())).block();
        turnOne.blockLast();

        // 回合二：error 轮，trust 无末轮处理 → 不应归因回合一的 report
        assertThatThrownBy(() -> mw.onAgent(null, rc, new AgentInput(List.of()),
                input -> Flux.error(new IllegalStateException("mid-turn boom"))).blockLast())
                .isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<TurnObservation> captor = ArgumentCaptor.forClass(TurnObservation.class);
        verify(recorder, times(2)).recordTurn(captor.capture(), any());

        TurnObservation merged = captor.getAllValues().get(0);
        assertThat(merged.trustStats()).isNotNull();
        assertThat(merged.trustStats()).containsKeys(
                "verified", "sourced", "unverified", "corrections", "correctionFailures", "exempted");
        assertThat(merged.trustStats().get("verified")).isEqualTo(1);
        // Task 8 传导约束：值域保持标量（JSONB 序列化失败防护）
        assertThat(merged.trustStats().values()).allSatisfy(v -> assertThat(v).isInstanceOf(Number.class));
        assertThat(merged.toolCount()).isEqualTo(1);

        TurnObservation failedTurn = captor.getAllValues().get(1);
        assertThat(failedTurn.failed()).isTrue();
        assertThat(failedTurn.trustStats()).isNull();
    }

    @Test
    @DisplayName("标识符：userId/sessionId 直取 ctx；messageId 优先 RC_KEY 复合 id 回退 Msg id")
    void givenIdentifiers_whenTurnEnds_thenTakenFromContextOrFallback() {
        RuntimeContext rc = ctx("7", "conv-9");
        rc.put(TrustWireMessageIdMiddleware.RC_KEY, "wire-1");
        ObservabilityAgentMiddleware mw = newMiddleware();
        mw.onAgent(null, rc, new AgentInput(List.of()),
                input -> normalStream("r-1", null, msg("msg-77"))).blockLast();

        RuntimeContext rcNoWire = RuntimeContext.builder().userId("abc").sessionId("conv-9").build();
        mw.onAgent(null, rcNoWire, new AgentInput(List.of()),
                input -> normalStream("r-2", null, msg("msg-77"))).blockLast();

        ArgumentCaptor<TurnObservation> captor = ArgumentCaptor.forClass(TurnObservation.class);
        verify(recorder, times(2)).recordTurn(captor.capture(), any());

        TurnObservation withWire = captor.getAllValues().get(0);
        assertThat(withWire.userId()).isEqualTo(7L);
        assertThat(withWire.conversationId()).isEqualTo("conv-9");
        assertThat(withWire.messageId()).isEqualTo("wire-1");

        TurnObservation fallback = captor.getAllValues().get(1);
        assertThat(fallback.messageId()).isEqualTo("msg-77");
        assertThat(fallback.userId()).isNull();
    }

    @Test
    @DisplayName("工具实录映射：TrustContext 当轮实录 → 观测行全字段（decorator 播种）")
    void givenToolInvocationsInTrustContext_whenTurnEnds_thenMappedObservations() throws Exception {
        RuntimeContext rc = ctx("7", "conv-1");
        ObservabilityAgentMiddleware mw = newMiddleware();
        seedToolCall(rc, "get_quote", false,
                Mono.just(ToolResultBlock.text(POOL_JSON)), Map.of("code", "600519"));
        seedToolCall(rc, "mcp_search", true,
                Mono.error(new IllegalStateException("下游超时")), Map.of("q", "茅台"));
        mw.onAgent(null, rc, new AgentInput(List.of()),
                input -> normalStream("r-1", null, msg("m-1"))).blockLast();

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

    // ———— 防御路径（原 :439 逐字移植） ————

    @Test
    @DisplayName("rc 缺席：流照常落库，标识符与 token 容忍 null（旁路 best-effort）")
    void givenNoRuntimeContext_whenTurnEnds_thenStillRecordedWithNullIdentifiers() {
        ObservabilityAgentMiddleware mw = newMiddleware();

        mw.onAgent(null, null, new AgentInput(List.of()),
                input -> normalStream("r-1", null, msg("m-1"))).blockLast();

        TurnObservation turn = capturedTurn();
        assertThat(turn.userId()).isNull();
        assertThat(turn.conversationId()).isNull();
        assertThat(turn.messageId()).isEqualTo("m-1");
        assertThat(turn.durationMs()).isNotNull();
        assertThat(turn.toolCount()).isZero();
    }

    // ———— 夹具 ————

    private TurnObservation capturedTurn() {
        ArgumentCaptor<TurnObservation> captor = ArgumentCaptor.forClass(TurnObservation.class);
        verify(recorder).recordTurn(captor.capture(), any());
        return captor.getValue();
    }

    private static Msg msg(String id) {
        return Msg.builder().id(id).role(MsgRole.ASSISTANT).textContent("轮收尾").build();
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

    /** 可编程 stub 工具（原测同款）：behavior 决定 callAsync 行为。 */
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
}
