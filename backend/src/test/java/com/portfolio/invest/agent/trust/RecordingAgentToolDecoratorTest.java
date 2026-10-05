package com.portfolio.invest.agent.trust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolEmitter;
import io.agentscope.core.tool.Toolkit;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * 工具真值捕获装饰器（MS-29 B3，设计规格 §三）：委托面透传不变形（含权限面——非 ToolBase 会被
 * ReActAgent 权限门直接放行，装饰器必须 extends ToolBase 并委托 checkPermissions，ADR-0010 不回归）；
 * callAsync 旁路记录 (tool, args, resultText, emittedSpecs, asOf) 进真值池；失败记 failed=true 且异常照常传播。
 */
class RecordingAgentToolDecoratorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 固定时钟：UTC 01:30 = 北京 09:30 → call 时刻恒为 "2026-10-05 09:30:00"。 */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-05T01:30:00Z"), ZoneId.of("Asia/Shanghai"));
    private static final String CALL_TIME = "2026-10-05 09:30:00";

    @AfterEach
    void cleanThreadLocalFallback() {
        TrustContext.reset();
    }

    // ———— 测试替身 ————

    /** 可编程 stub：behavior 决定 callAsync 行为（返回/emit/抛错）；非 final——允许匿名覆写元数据面。 */
    static class StubTool extends ToolBase {
        private final Function<ToolCallParam, Mono<ToolResultBlock>> behavior;

        StubTool(String name, Function<ToolCallParam, Mono<ToolResultBlock>> behavior) {
            super(ToolBase.builder()
                    .name(name)
                    .description("验证用 stub 工具")
                    .inputSchema(Map.of("type", "object", "properties", Map.of(), "required", List.of()))
                    .readOnly(true)
                    .concurrencySafe(true));
            this.behavior = behavior;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return behavior.apply(param);
        }
    }

    /** 权限门 stub：恒 ASK（复刻 McpTool 非只读语义）——验证装饰器不吞权限决策。 */
    static final class AskPermissionTool extends StubTool {
        AskPermissionTool() {
            super("ask_tool", p -> Mono.just(ToolResultBlock.text("{}")));
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(
                Map<String, Object> toolInput, PermissionContextState context) {
            return Mono.just(PermissionDecision.ask("ask_tool requires explicit authorization"));
        }
    }

    /** 带原始 content JSON 的 ToolUseBlock（B0 教训：input 与 content 缺一会被 ToolValidator 拒）。 */
    private static ToolUseBlock use(String id, String name, Map<String, Object> input) {
        return new ToolUseBlock(id, name, input, MAPPER.valueToTree(input).toString(), Map.of());
    }

    private static ToolCallParam param(ToolUseBlock use, RuntimeContext rc) {
        return ToolCallParam.builder().toolUseBlock(use).input(use.getInput()).runtimeContext(rc).build();
    }

    private static RecordingAgentToolDecorator decorated(
            Function<ToolCallParam, Mono<ToolResultBlock>> behavior) {
        return decorated(behavior, false);
    }

    private static RecordingAgentToolDecorator decorated(
            Function<ToolCallParam, Mono<ToolResultBlock>> behavior, boolean mcp) {
        return new RecordingAgentToolDecorator(new StubTool("stub_tool", behavior), mcp, MAPPER, CLOCK);
    }

    @DisplayName("非 ToolBase 委托：接口默认面照常记录（并发安全按执行器缺省 true）")
    @Test
    void givenPlainAgentToolDelegate_whenCallAsync_thenStillRecorded() {
        RuntimeContext rc = RuntimeContext.empty();
        AgentTool plain = new AgentTool() {
            @Override
            public String getName() {
                return "plain_tool";
            }

            @Override
            public String getDescription() {
                return "仅实现接口的裸工具";
            }

            @Override
            public Map<String, Object> getParameters() {
                return Map.of("type", "object");
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.just(ToolResultBlock.text("{\"tradeDate\":\"2026-09-30\"}"));
            }
        };
        RecordingAgentToolDecorator decorator = new RecordingAgentToolDecorator(plain, false, MAPPER, CLOCK);

        assertThat(decorator.isConcurrencySafe()).as("非 ToolBase 委托对齐执行器缺省（true）").isTrue();
        assertThat(decorator.isMcp()).isFalse();
        decorator.callAsync(param(use("call_p", "plain_tool", Map.of()), rc)).block();

        ToolInvocation invocation = TrustContext.current(rc).invocations().get(0);
        assertThat(invocation.toolName()).isEqualTo("plain_tool");
        assertThat(invocation.asOf()).isEqualTo("2026-09-30");
        assertThat(invocation.asOfKind()).isEqualTo(ToolInvocation.AsOfKind.DATA);
    }

    @DisplayName("无 toolUseId：旁路记录静默降级为不记，调用语义不变")
    @Test
    void givenNoToolUseId_whenCallAsync_thenBypassRecordingButCallSucceeds() {
        RuntimeContext rc = RuntimeContext.empty();
        RecordingAgentToolDecorator decorator = decorated(
                p -> Mono.just(ToolResultBlock.text("{\"time\":\"09:30:15\"}")));

        ToolResultBlock result = decorator.callAsync(param(use(null, "stub_tool", Map.of()), rc)).block();

        assertThat(textOf(result)).isEqualTo("{\"time\":\"09:30:15\"}");
        assertThat(TrustContext.current(rc).invocations()).as("无法归位则不记（绝不改调用语义）").isEmpty();
    }

    // ———— 委托面 ————

    @DisplayName("委托面透传：name/description/parameters/isReadOnly/getStrict/getOutputSchema 不变形")
    @Test
    void givenDecorated_whenSurface_thenDelegatesUntouched() {
        AgentTool inner = new StubTool("quote_x", p -> Mono.just(ToolResultBlock.text("{}"))) {
            @Override
            public Map<String, Object> getOutputSchema() {
                return Map.of("type", "object");
            }

            @Override
            public Boolean getStrict() {
                return true;
            }
        };
        RecordingAgentToolDecorator decorator =
                new RecordingAgentToolDecorator(inner, false, MAPPER, CLOCK);

        assertThat(decorator.getName()).isEqualTo("quote_x");
        assertThat(decorator.getDescription()).isEqualTo("验证用 stub 工具");
        assertThat(decorator.getParameters()).isEqualTo(inner.getParameters());
        assertThat(decorator.isReadOnly()).isTrue();
        assertThat(decorator.getStrict()).isEqualTo(true);
        assertThat(decorator.getOutputSchema()).isEqualTo(Map.of("type", "object"));
    }

    @DisplayName("ToolBase 元数据保真：concurrencySafe/mcp 名随委托复制（并发分组与 MCP 标识不回归）")
    @Test
    void givenToolBaseDelegate_whenDecorated_thenFlagsCopied() {
        ToolBase mcpLike = new ToolBase(ToolBase.builder()
                .name("tushare_q").description("d")
                .inputSchema(Map.of("type", "object"))
                .readOnly(false).concurrencySafe(false).mcp("tushare")) {
            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.just(ToolResultBlock.text("{}"));
            }
        };
        RecordingAgentToolDecorator decorator =
                new RecordingAgentToolDecorator(mcpLike, true, MAPPER, CLOCK);

        assertThat(decorator.isConcurrencySafe()).as("并发安全标志随委托复制").isFalse();
        assertThat(decorator.isMcp()).as("MCP 标志随委托复制").isTrue();
        assertThat(decorator.getMcpName()).isEqualTo("tushare");
        assertThat(decorator.isReadOnly()).isFalse();
    }

    @DisplayName("权限面委托：委托工具 ASK → 装饰器同样 ASK（HITL 审批不因装饰而放行）")
    @Test
    void givenAskPermissionDelegate_whenCheckPermissions_thenAskPreserved() {
        RecordingAgentToolDecorator decorator =
                new RecordingAgentToolDecorator(new AskPermissionTool(), true, MAPPER, CLOCK);

        PermissionDecision decision = decorator
                .checkPermissions(Map.of(), null).block();

        assertThat(decision.getBehavior()).as("非只读工具的权限 ASK 必须经装饰器透传（ADR-0010）")
                .isEqualTo(PermissionBehavior.ASK);
    }

    // ———— 真值记录 ————

    @DisplayName("callAsync 成功后真值池含 (tool, args, resultText, emittedSpecs, asOf)")
    @Test
    void givenJsonTextResult_whenCallAsync_thenInvocationRecorded() {
        RuntimeContext rc = RuntimeContext.empty();
        RecordingAgentToolDecorator decorator = decorated(
                p -> Mono.just(ToolResultBlock.text("{\"time\":\"09:30:15\",\"price\":15.2}")));

        decorator.callAsync(param(use("call_1", "stub_tool", Map.of("code", "600519")), rc)).block();

        List<ToolInvocation> pool = TrustContext.current(rc).invocations();
        assertThat(pool).hasSize(1);
        ToolInvocation invocation = pool.get(0);
        assertThat(invocation.toolName()).isEqualTo("stub_tool");
        assertThat(invocation.args()).containsEntry("code", "600519");
        assertThat(invocation.resultText()).isEqualTo("{\"time\":\"09:30:15\",\"price\":15.2}");
        assertThat(invocation.emittedSpecs()).isEmpty();
        assertThat(invocation.asOf()).as("get_quote 类 String-JSON 解析 time 字段").isEqualTo("09:30:15");
        assertThat(invocation.asOfKind()).isEqualTo(ToolInvocation.AsOfKind.DATA);
        assertThat(invocation.failed()).isFalse();
    }

    @DisplayName("args 为入参快照：记录后改原 map 不影响已记真值")
    @Test
    void givenMutableInput_whenCallAsync_thenArgsSnapshotted() {
        RuntimeContext rc = RuntimeContext.empty();
        Map<String, Object> input = new LinkedHashMap<>(Map.of("code", "600519"));
        RecordingAgentToolDecorator decorator = decorated(
                p -> Mono.just(ToolResultBlock.text("{\"time\":\"09:30:15\"}")));

        decorator.callAsync(param(use("call_1", "stub_tool", input), rc)).block();
        input.put("code", "000001");

        assertThat(TrustContext.current(rc).invocations().get(0).args())
                .as("真值池条目须与后续入参变更隔离").containsEntry("code", "600519");
    }

    @DisplayName("emit 块按 toolUseId 归位进当次调用；他路 id 不串池")
    @Test
    void givenEmission_whenCaptured_thenRoutedByToolUseId() {
        RuntimeContext rc = RuntimeContext.empty();
        ToolUseBlock mine = use("call_mine", "stub_tool", Map.of());
        ToolUseBlock other = use("call_other", "stub_tool", Map.of());
        String specJson = "{\"type\":\"table\",\"rows\":[{\"reportDate\":\"2026-06-30\"}]}";
        RecordingAgentToolDecorator decorator = decorated(p -> {
            // 模拟执行期 emit：user 级 chunkCallback 收到的两块，一块归位本调用、一块他路
            RecordingAgentToolDecorator.captureEmission(mine, ToolResultBlock.text(specJson));
            RecordingAgentToolDecorator.captureEmission(other, ToolResultBlock.text("{\"别的\":\"块\"}"));
            return Mono.just(ToolResultBlock.text("最新报告期 2026-06-30（纯文本摘要）"));
        });

        decorator.callAsync(param(mine, rc)).block();

        ToolInvocation invocation = TrustContext.current(rc).invocations().get(0);
        assertThat(invocation.emittedSpecs()).as("仅本 toolUseId 的 emit 块入池").containsExactly(specJson);
        assertThat(invocation.asOf()).as("结果文本无可解析 JSON 时取 emit spec 行内 reportDate").isEqualTo("2026-06-30");
        assertThat(invocation.asOfKind()).isEqualTo(ToolInvocation.AsOfKind.DATA);
    }

    @DisplayName("asOf 时点语义：generatedAt → GENERATED；无任何可解析时点 → 调用时刻 CALL")
    @Test
    void givenNoDataTimeFields_whenCallAsync_thenFallsBackToCallTime() {
        RuntimeContext generatedRc = RuntimeContext.empty();
        RuntimeContext callRc = RuntimeContext.empty();
        RecordingAgentToolDecorator generated = decorated(
                p -> Mono.just(ToolResultBlock.text("{\"indicators\":[],\"generatedAt\":\"2026-10-05 09:00\"}")));
        RecordingAgentToolDecorator plain = decorated(
                p -> Mono.just(ToolResultBlock.text("大盘速览(09:30:15)：上证指数 3990.30")));

        generated.callAsync(param(use("g", "stub_tool", Map.of()), generatedRc)).block();
        plain.callAsync(param(use("c", "stub_tool", Map.of()), callRc)).block();

        ToolInvocation generatedInvocation = TrustContext.current(generatedRc).invocations().get(0);
        assertThat(generatedInvocation.asOf()).isEqualTo("2026-10-05 09:00");
        assertThat(generatedInvocation.asOfKind()).isEqualTo(ToolInvocation.AsOfKind.GENERATED);
        ToolInvocation callInvocation = TrustContext.current(callRc).invocations().get(0);
        assertThat(callInvocation.asOf()).as("无可解析时点 → call 时刻（注入时钟）").isEqualTo(CALL_TIME);
        assertThat(callInvocation.asOfKind()).isEqualTo(ToolInvocation.AsOfKind.CALL);
    }

    @DisplayName("get_market_overview 生成时刻语义：解析到的 time 归 GENERATED 而非 DATA（值不变）")
    @Test
    void givenOverviewToolWithParsedTime_whenCallAsync_thenGeneratedKindNotData() {
        RuntimeContext rc = RuntimeContext.empty();
        // MarketOverview.time 三条解析路径全为本机 now（设计规格 §6.1）——同值不同义，须降格 GENERATED
        RecordingAgentToolDecorator decorator = new RecordingAgentToolDecorator(
                new StubTool("get_market_overview",
                        p -> Mono.just(ToolResultBlock.text("{\"time\":\"2026-10-05 09:30\",\"indices\":[]}"))),
                false, MAPPER, CLOCK);

        decorator.callAsync(param(use("call_ov", "get_market_overview", Map.of()), rc)).block();

        ToolInvocation invocation = TrustContext.current(rc).invocations().get(0);
        assertThat(invocation.asOf()).as("时间仍取解析到的 time，不退调用时刻").isEqualTo("2026-10-05 09:30");
        assertThat(invocation.asOfKind()).isEqualTo(ToolInvocation.AsOfKind.GENERATED);
    }

    @DisplayName("get_market_overview 无可解析时点：GENERATED 特例不改 best-effort 阶梯，仍 CALL 兜底")
    @Test
    void givenOverviewToolWithoutParsableTime_whenCallAsync_thenCallFallbackUnchanged() {
        RuntimeContext rc = RuntimeContext.empty();
        // 生产形态：overviewSummary 纯文本 + bar spec 均无 JSON 时点字段——特例只改归类不改解析来源
        RecordingAgentToolDecorator decorator = new RecordingAgentToolDecorator(
                new StubTool("get_market_overview",
                        p -> Mono.just(ToolResultBlock.text("大盘速览(2026-10-05 09:30)：上证指数 3990.30"))),
                false, MAPPER, CLOCK);

        decorator.callAsync(param(use("call_ov2", "get_market_overview", Map.of()), rc)).block();

        ToolInvocation invocation = TrustContext.current(rc).invocations().get(0);
        assertThat(invocation.asOf()).isEqualTo(CALL_TIME);
        assertThat(invocation.asOfKind()).isEqualTo(ToolInvocation.AsOfKind.CALL);
    }

    @DisplayName("MCP 工具恒 sourced：结果含 time 仍记 asOfKind=CALL + 调用时刻（决策 #5，不入比对池）")
    @Test
    void givenMcpDecorator_whenResultHasTime_thenAlwaysCallKind() {
        RuntimeContext rc = RuntimeContext.empty();
        RecordingAgentToolDecorator decorator = decorated(
                p -> Mono.just(ToolResultBlock.text("{\"time\":\"09:30:15\",\"close\":3900.5}")), true);

        decorator.callAsync(param(use("call_1", "stub_tool", Map.of()), rc)).block();

        ToolInvocation invocation = TrustContext.current(rc).invocations().get(0);
        assertThat(invocation.asOf()).isEqualTo(CALL_TIME);
        assertThat(invocation.asOfKind()).isEqualTo(ToolInvocation.AsOfKind.CALL);
    }

    @DisplayName("工具失败：failed=true 入池供 B7 信号，异常照常向下游传播")
    @Test
    void givenFailingTool_whenCallAsync_thenFailedRecordedAndErrorPropagates() {
        RuntimeContext rc = RuntimeContext.empty();
        RecordingAgentToolDecorator decorator = decorated(
                p -> Mono.error(new IllegalStateException("行情源不可用")));

        assertThatThrownBy(() ->
                decorator.callAsync(param(use("call_1", "stub_tool", Map.of("code", "600519")), rc)).block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("行情源不可用");

        ToolInvocation invocation = TrustContext.current(rc).invocations().get(0);
        assertThat(invocation.failed()).isTrue();
        assertThat(invocation.toolName()).isEqualTo("stub_tool");
        assertThat(invocation.args()).containsEntry("code", "600519");
        assertThat(invocation.asOfKind()).isEqualTo(ToolInvocation.AsOfKind.CALL);
    }

    @DisplayName("同一回合多次调用累积入同一真值池")
    @Test
    void givenTwoCalls_whenSameRuntimeContext_thenPoolAccumulates() {
        RuntimeContext rc = RuntimeContext.empty();
        RecordingAgentToolDecorator decorator = decorated(
                p -> Mono.just(ToolResultBlock.text("{\"time\":\"09:30:15\"}")));

        decorator.callAsync(param(use("c1", "stub_tool", Map.of()), rc)).block();
        decorator.callAsync(param(use("c2", "stub_tool", Map.of()), rc)).block();

        assertThat(TrustContext.current(rc).invocations()).hasSize(2);
    }

    // ———— 与 Toolkit 的装配级联动（B0 探针 1 结论的行为锁定） ————

    @DisplayName("Toolkit 级端到端：user 级 chunkCallback + 装饰器注册后，emit 块进真值池、返回文本不回归")
    @Test
    void givenToolkitWiring_whenCallTool_thenEmissionCapturedThroughRealCallback() {
        Toolkit toolkit = new Toolkit();
        toolkit.setChunkCallback(RecordingAgentToolDecorator::captureEmission);
        String specJson = "{\"type\":\"table\",\"rows\":[{\"reportDate\":\"2026-06-30\"}]}";
        toolkit.registerAgentTool(new RecordingAgentToolDecorator(
                new StubTool("emit_tool", p -> {
                    ToolEmitter emitter = p.getEmitter();
                    assertThat(emitter).as("executeCore 须为工具装配 emitter").isNotNull();
                    emitter.emit(ToolResultBlock.text(specJson));
                    return Mono.just(ToolResultBlock.text("最新报告期 2026-06-30"));
                }), false, MAPPER, CLOCK));
        RuntimeContext rc = RuntimeContext.empty();

        ToolResultBlock result = toolkit.callTool(
                param(use("call_w", "emit_tool", Map.of()), rc)).block();

        assertThat(textOf(result)).isEqualTo("最新报告期 2026-06-30");
        ToolInvocation invocation = TrustContext.current(rc).invocations().get(0);
        assertThat(invocation.toolName()).isEqualTo("emit_tool");
        assertThat(invocation.emittedSpecs()).as("emit 块经真实 user chunkCallback 归位").containsExactly(specJson);
        assertThat(invocation.resultText()).as("返回文本通道不受 emit 捕获影响").isEqualTo("最新报告期 2026-06-30");
    }

    private static String textOf(ToolResultBlock block) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : block.getOutput()) {
            if (b instanceof TextBlock t && t.getText() != null) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(t.getText());
            }
        }
        return sb.toString();
    }
}
