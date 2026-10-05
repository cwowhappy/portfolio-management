package com.portfolio.invest.agent.trust;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import reactor.core.publisher.Mono;

/**
 * 工具真值捕获装饰器（MS-29 B3，设计规格 §三）：同名覆盖注册进 Toolkit，对每次工具调用旁路记录
 * {@link ToolInvocation}（参数/结果文本/emit 块/数据时点）进 {@link TrustContext} 真值池，供 B5 锚定
 * 校验与 B7 信号消费。<strong>只旁路记录，不改调用语义</strong>——失败照常抛、返回值原样透传。
 *
 * <p><strong>必须 extends ToolBase（非可选）</strong>：ReActAgent 权限门对注册表里非 ToolBase 的
 * 工具直接放行——若本装饰器仅实现 AgentTool，包裹 McpTool 会使非只读 MCP 工具的 HITL 审批静默失效
 * （ADR-0010 回归）。故权限面（checkPermissions/generateSuggestions）显式委托给被包工具。
 *
 * <p><strong>emit 捕获（B0 探针 1 结论）</strong>：user 级 chunkCallback 由装配方
 * （UserToolkitFactory）经 {@code Toolkit.setChunkCallback} 挂一次（单值替换语义），指向
 * {@link #captureEmission}；emit 的 ToolResultBlock 按 toolUseId 归位进在途调用的 emittedSpecs，
 * 与既有 SSE 图表通道（internal 回调）互不扰动。
 *
 * <p><strong>asOf 时点（best-effort：可解析数据时点 &gt; 调用时刻）</strong>：结果文本 JSON 的
 * 时点字段（time/tradeDate/tradingDay/reportDate/date/asOf/updatedAt）→ DATA；generatedAt →
 * GENERATED；emit spec（ChartSpec JSON）顶层与 Table 首行同序扫描（B4 透出 DTO 侧字段后自然命中）；
 * 无可解析时点 → 调用时刻 CALL。MCP 工具恒 CALL（决策 #5：sourced 语义，真值不入比对池）。
 * {@code get_market_overview} 特例（§6.1）：time 为本机生成时刻，解析到的时点归 GENERATED 而非 DATA。
 */
public class RecordingAgentToolDecorator extends ToolBase {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter CALL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 数据时点候选字段（序即优先级；B4 的 tradeDate/tradingDay 已预留命中位）。 */
    private static final String[] DATA_TIME_KEYS =
            {"time", "tradeDate", "tradingDay", "reportDate", "date", "asOf", "updatedAt"};
    private static final String GENERATED_TIME_KEY = "generatedAt";

    /**
     * time 语义为本机生成时刻的工具（设计规格 §6.1，MS-29 B4）：其 time 字段三条解析路径全为
     * 本机 now（MarketDataParser）——同值不同义，解析到的时点降格 GENERATED（值不变）；无可解析
     * 时点仍走 CALL 兜底（best-effort 阶梯不变）。
     */
    private static final Set<String> GENERATED_TIME_TOOLS = Set.of("get_market_overview");

    /** 在途调用的 emit 收集走廊：toolUseId → 该调用已 emit 的块文本（跨线程安全，doFinally 摘除）。 */
    private static final Map<String, ConcurrentLinkedQueue<String>> EMISSIONS = new ConcurrentHashMap<>();

    private final AgentTool delegate;
    /** MCP 录制语义：恒 asOfKind=CALL（sourced，不入比对池，决策 #5）。 */
    private final boolean mcp;
    private final ObjectMapper mapper;
    private final Clock clock;

    public RecordingAgentToolDecorator(AgentTool delegate, boolean mcp, ObjectMapper mapper) {
        this(delegate, mcp, mapper, Clock.system(ZONE));
    }

    RecordingAgentToolDecorator(AgentTool delegate, boolean mcp, ObjectMapper mapper, Clock clock) {
        super(builderFrom(delegate));
        this.delegate = delegate;
        this.mcp = mcp;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** 委托面元数据复制（权限门/并发分组/MCP 标识都读 ToolBase 字段——不复制即行为回归）。 */
    private static ToolBase.Builder builderFrom(AgentTool delegate) {
        ToolBase.Builder builder = ToolBase.builder()
                .name(delegate.getName())
                .description(delegate.getDescription() == null ? "" : delegate.getDescription())
                .inputSchema(delegate.getParameters() == null ? Map.of() : delegate.getParameters())
                .readOnly(delegate.isReadOnly())
                // 非 ToolBase 委托时对齐 ToolExecutor 的缺省判定（isConcurrencySafe 对非 ToolBase 恒 true）
                .concurrencySafe(!(delegate instanceof ToolBase base) || base.isConcurrencySafe())
                .externalTool(delegate instanceof ToolBase base && base.isExternalTool())
                .stateInjected(delegate instanceof ToolBase base && base.isStateInjected());
        if (delegate instanceof ToolBase base && base.getMcpName() != null) {
            builder.mcp(base.getMcpName());
        }
        return builder;
    }

    // ———— 委托面（接口默认实现不走基类字段，须显式转发） ————

    @Override
    public Boolean getStrict() {
        return delegate.getStrict();
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return delegate.getOutputSchema();
    }

    // ———— 权限面（HITL 不回归，ADR-0010） ————

    @Override
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> toolInput, PermissionContextState context) {
        if (delegate instanceof ToolBase base) {
            return base.checkPermissions(toolInput, context);
        }
        return super.checkPermissions(toolInput, context);
    }

    @Override
    public List<PermissionRule> generateSuggestions(Map<String, Object> toolInput) {
        if (delegate instanceof ToolBase base) {
            return base.generateSuggestions(toolInput);
        }
        return super.generateSuggestions(toolInput);
    }

    // ———— 录制 ————

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        ToolUseBlock use = param == null ? null : param.getToolUseBlock();
        if (use == null || use.getId() == null) {
            // 无 toolUseId 无法归位 emit 与回查池——旁路记录静默降级为不记（绝不改调用语义）
            return delegate.callAsync(param);
        }
        String callTime = LocalDateTime.now(clock).format(CALL_TIME);
        Map<String, Object> raw = param.getInput() != null ? param.getInput() : use.getInput();
        Map<String, Object> args = raw == null ? Map.of() : new LinkedHashMap<>(raw);
        return Mono.defer(() -> {
            ConcurrentLinkedQueue<String> emissions = new ConcurrentLinkedQueue<>();
            EMISSIONS.put(use.getId(), emissions);
            return delegate.callAsync(param)
                    .doOnSuccess(result -> record(args, result, emissions, callTime, param.getRuntimeContext()))
                    .doOnError(error -> recordFailure(args, emissions, callTime, param.getRuntimeContext()))
                    .doFinally(signal -> EMISSIONS.remove(use.getId()));
        });
    }

    private void record(Map<String, Object> args, ToolResultBlock result,
                        ConcurrentLinkedQueue<String> emissions, String callTime, RuntimeContext rc) {
        String resultText = result == null ? "" : textOf(result);
        List<String> specs = List.copyOf(emissions);
        AsOf asOf = resolveAsOf(resultText, specs, callTime);
        TrustContext.current(rc).record(new ToolInvocation(
                delegate.getName(), args, resultText, specs, asOf.value(), asOf.kind(), false));
    }

    private void recordFailure(Map<String, Object> args, ConcurrentLinkedQueue<String> emissions,
                               String callTime, RuntimeContext rc) {
        TrustContext.current(rc).record(new ToolInvocation(
                delegate.getName(), args, "", List.copyOf(emissions), callTime,
                ToolInvocation.AsOfKind.CALL, true));
    }

    /** user 级 chunkCallback 目标（装配期挂一次）：emit 块按 toolUseId 归位进在途调用。 */
    public static void captureEmission(ToolUseBlock use, ToolResultBlock block) {
        if (use == null || use.getId() == null || block == null) {
            return;
        }
        ConcurrentLinkedQueue<String> collector = EMISSIONS.get(use.getId());
        if (collector != null) {
            collector.add(textOf(block));
        }
    }

    // ———— asOf 解析 ————

    private AsOf resolveAsOf(String resultText, List<String> emittedSpecs, String callTime) {
        if (mcp) {
            return new AsOf(callTime, ToolInvocation.AsOfKind.CALL);
        }
        AsOf parsed = scan(resultText);
        if (parsed == null) {
            for (String spec : emittedSpecs) {
                parsed = scan(spec);
                if (parsed != null) {
                    break;
                }
            }
        }
        if (parsed == null) {
            return new AsOf(callTime, ToolInvocation.AsOfKind.CALL);
        }
        // 生成时刻语义工具（§6.1）：time 为本机 now 而非数据自带时点——值保留、kind 降格 GENERATED
        return GENERATED_TIME_TOOLS.contains(delegate.getName())
                ? new AsOf(parsed.value(), ToolInvocation.AsOfKind.GENERATED)
                : parsed;
    }

    /** 单个 JSON 文档扫描：顶层时点字段 → Table 首行时点字段。 */
    private AsOf scan(String json) {
        JsonNode root = parse(json);
        if (root == null || !root.isObject()) {
            return null;
        }
        AsOf fromTop = scanObject(root);
        if (fromTop != null) {
            return fromTop;
        }
        JsonNode firstRow = root.path("rows").path(0);
        return firstRow.isObject() ? scanObject(firstRow) : null;
    }

    private static AsOf scanObject(JsonNode obj) {
        for (String key : DATA_TIME_KEYS) {
            String value = textValue(obj, key);
            if (value != null) {
                return new AsOf(value, ToolInvocation.AsOfKind.DATA);
            }
        }
        String generated = textValue(obj, GENERATED_TIME_KEY);
        return generated != null ? new AsOf(generated, ToolInvocation.AsOfKind.GENERATED) : null;
    }

    private static String textValue(JsonNode obj, String key) {
        JsonNode node = obj.get(key);
        if (node == null || node.isNull()) {
            return null;
        }
        String text = node.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return null; // 非 JSON（纯文本摘要）——best-effort 静默跳过
        }
    }

    /** 块文本拼接：output 里的 TextBlock 依次连接（与 LLM 看到的文本通道一致）。 */
    private static String textOf(ToolResultBlock block) {
        if (block.getOutput() == null) {
            return "";
        }
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

    /** 解析出的时点（value + 语义），仅装饰器内部使用。 */
    private record AsOf(String value, ToolInvocation.AsOfKind kind) {}
}
