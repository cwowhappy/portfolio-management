package com.portfolio.invest.agent.trust;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.agent.AguiEventNonNullCodec;
import com.portfolio.invest.config.InvestProperties;
import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.util.JacksonJsonCodec;
import io.agentscope.core.util.JsonCodec;
import io.agentscope.core.util.JsonUtils;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 信任事件线上契约锁定（MS-29 B10）：{@code trust.anchors} / {@code trust.correction} 的
 * **满配 payload** 经真实线上序列化路径（{@code AguiWireJsonConfig} 启动装配的全局 codec +
 * agentscope {@code AguiEventEncoder}）编码后——
 *
 * <ul>
 *   <li><strong>无 null 契约</strong>：整帧 JSON（含嵌套 Map 深层，如 anchor.args 内 null 值与
 *       嵌套对象内 null 值）不出现任何 null 字段/元素——{@code setSerializationInclusion(NON_NULL)}
 *       的属性级+内容级双剥除（#25 机制，防 agentscope/jackson 升级改变 NON_NULL 语义时静默回归）；</li>
 *   <li><strong>缺键契约</strong>：未携带的可选域（correction/advice/confidence；unverified 锚定的
 *       tool/args/asOf/asOfKind/raw）**整键不出现**，而非 null 占位（payload v1 §2.1）；</li>
 *   <li><strong>对照组非空转</strong>：同一事件经默认 codec（无 NON_NULL）确实写出
 *       {@code "market":null}——证明上面缺 null 是剥除机制的产物，断言不是空转绿。</li>
 * </ul>
 *
 * <p>事件半边驱动真实 {@link TrustAgentHook}（捕获其发射的 CustomEvent 原对象，与生产同构）；
 * 线上半边把 value 装进 {@code AguiEvent$Custom}——与库内 {@code CustomAgentEventConverter}
 * 的转换等价（javap 核实 2.0.3 为四参透传构造，无拷贝/改写），再经
 * {@link AguiEventEncoder#encode}（内部动态取 {@code JsonUtils.getJsonCodec()}）出帧。
 * codec 装配与包私有 {@code AguiWireJsonConfig} 逐行同构（该配置类不可跨包引用），测试内
 * save/restore 全局 codec 不污染同 JVM 顺序执行的其余用例。
 */
class TrustEventWireContractTest {

    /** 固定时钟：陈旧度「今日」= 2026-10-06（Asia/Shanghai，与生产装配同时区）。 */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-05T18:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private static final ObjectMapper TREE = new ObjectMapper();

    private final AguiEventEncoder encoder = new AguiEventEncoder();
    private JsonCodec previousCodec;

    @BeforeEach
    void installProductionWireCodec() {
        previousCodec = JsonUtils.getJsonCodec();
        JacksonJsonCodec fallback = new JacksonJsonCodec();
        JsonUtils.setJsonCodec(new AguiEventNonNullCodec(
                fallback,
                new JacksonJsonCodec(fallback.getObjectMapper()
                        .copy()
                        .setSerializationInclusion(JsonInclude.Include.NON_NULL))));
    }

    @AfterEach
    void restoreWireCodec() {
        JsonUtils.setJsonCodec(previousCodec);
    }

    // ———— 满配形态：全 optional 域齐备（correction+advice+confidence）、anchors 三态齐备 ————

    @DisplayName("满配 trust.anchors：全键上线、深层无 null（含 args 内 null 值与嵌套 null）")
    @Test
    void givenFullFeaturedTurn_whenEncodeAnchorsEvent_thenAllKeysPresentAndNoNullAnywhere() throws Exception {
        CustomEvent anchors = runFullFeaturedTurn().stream()
                .filter(e -> TrustAgentHook.ANCHORS_EVENT.equals(e.getName()))
                .findFirst().orElseThrow();
        JsonNode payload = encodeAndParse(anchors).get("value").get("payload");

        // 顶层满配：v/anchors/stats/correction/advice/confidence 全部在键
        assertThat(payload.fieldNames()).toIterable().containsExactly(
                "v", "anchors", "stats", "correction", "advice", "confidence");
        assertThat(payload.get("v").asInt()).isEqualTo(1);

        // verified 锚定：八键满配（snippet/occ/state/tool/args/asOf/asOfKind/raw）
        JsonNode verified = anchorOfState(payload, "verified");
        assertThat(verified.fieldNames()).toIterable()
                .containsExactly("snippet", "occ", "state", "tool", "args", "asOf", "asOfKind", "raw");
        assertThat(verified.get("tool").asText()).isEqualTo("get_quote");
        assertThat(verified.get("asOfKind").asText()).isEqualTo("data");
        // args 深层 null 剥除：null 值键（market）与嵌套对象内 null（meta.region）整键不出现，
        // 非 null 键保留——工具参数显式传 null 是真实形态（LLM 可选参），线上绝不回 null
        assertThat(verified.get("args").get("code").asText()).isEqualTo("600519");
        assertThat(verified.get("args").has("market")).isFalse();
        assertThat(verified.get("args").get("meta").get("src").asText()).isEqualTo("east");
        assertThat(verified.get("args").get("meta").has("region")).isFalse();

        // sourced 锚定（MCP 真值配源）：同为八键满配
        JsonNode sourced = anchorOfState(payload, "sourced");
        assertThat(sourced.fieldNames()).toIterable()
                .containsExactly("snippet", "occ", "state", "tool", "args", "asOf", "asOfKind", "raw");
        assertThat(sourced.get("asOfKind").asText()).isEqualTo("call");

        // advice 满配三键（B6：flag/by/text）
        assertThat(payload.get("advice").fieldNames()).toIterable().containsExactly("flag", "by", "text");
        assertThat(payload.get("advice").get("flag").asBoolean()).isTrue();
        assertThat(payload.get("advice").get("by").asText()).isEqualTo("both");
        assertThat(payload.get("advice").get("text").asText())
                .isEqualTo(new InvestProperties().getTrust().getDisclaimerText());

        // confidence 满配（B7 四类机制信号本轮全命中：ratio/陈旧/失败/修正）
        List<String> signals = new ArrayList<>();
        payload.get("confidence").get("signals").forEach(s -> signals.add(s.asText()));
        assertThat(signals).containsExactly(
                "unverified_ratio:0.57", "stale_quotes:2", "tool_failures:1", "corrections:1");

        // correction 满配（B2 注记）
        assertThat(payload.get("correction").get("notes").get(0).asText()).isEqualTo("原文误述 15.20元");

        // 整树递归：任何深度的对象 null 字段 / 数组 null 元素都不存在
        assertThat(nullPaths(encodeAndParse(anchors), "$")).isEmpty();
    }

    @DisplayName("满配 trust.correction：五键全上线且无 null；messageId 为线上 id")
    @Test
    void givenCorrectionTurn_whenEncodeCorrectionEvent_thenFiveKeysPresentNoNull() throws Exception {
        CustomEvent correction = runFullFeaturedTurn().stream()
                .filter(e -> TrustAgentHook.CORRECTION_EVENT.equals(e.getName()))
                .findFirst().orElseThrow();
        JsonNode value = encodeAndParse(correction).get("value");

        assertThat(value.fieldNames()).toIterable()
                .containsExactly("messageId", "snippet", "occ", "replacement", "note");
        assertThat(value.get("messageId").asText()).isEqualTo("wire-msg-9");
        assertThat(value.get("snippet").asText()).isEqualTo("15.20元");
        assertThat(value.get("occ").asInt()).isEqualTo(1);
        assertThat(value.get("replacement").asText()).isEqualTo("1520.33元");
        assertThat(value.get("note").asText()).isEqualTo("原文误述 15.20元");
        assertThat(nullPaths(encodeAndParse(correction), "$")).isEmpty();
    }

    @DisplayName("对照组：同一事件经默认 codec（无 NON_NULL）确实写出深层 null——证明非空转")
    @Test
    void givenSameArgsNullEvent_whenDefaultCodec_thenDeepNullWritten() {
        // 默认 codec（delegate 半边，无 NON_NULL）对同一 AguiEvent$Custom 的序列化：
        // args.market / args.meta.region 的 null 原样上帧——正是 NON_NULL 副本要剥除的内容
        AguiEvent.Custom event = toAguiCustom(runFullFeaturedTurn().stream()
                .filter(e -> TrustAgentHook.ANCHORS_EVENT.equals(e.getName()))
                .findFirst().orElseThrow());
        assertThat(new JacksonJsonCodec().toJson(event)).contains("\"market\":null", "\"region\":null");
    }

    // ———— 缺键形态：无可选域时整键不出现（payload v1「无 null 用缺键」） ————

    @DisplayName("最小回合 trust.anchors：correction/advice/confidence 整键缺席，unverified 锚定只有三键")
    @Test
    void givenMinimalTurn_whenEncodeAnchorsEvent_thenOptionalDomainsAbsentByMissingKey() throws Exception {
        List<CustomEvent> events = runTurn(
                List.of(new ToolInvocation("get_quote", Map.of("code", "600519"),
                        "{\"price\":1520.33,\"time\":\"2026-10-06 09:30:00\"}", List.of(),
                        "2026-10-06 09:30:00", ToolInvocation.AsOfKind.DATA, false, false, 0L)),
                "现价1520.33元，毛利率58.2%。");

        assertThat(events).extracting(CustomEvent::getName)
                .containsExactly(TrustAgentHook.ANCHORS_EVENT);
        JsonNode payload = encodeAndParse(events.get(0)).get("value").get("payload");

        // 可选域缺键（不是 null 占位）：无修正、无建议命中、无低置信信号
        assertThat(payload.fieldNames()).toIterable().containsExactly("v", "anchors", "stats");
        assertThat(payload.toString()).doesNotContain("correction", "advice", "confidence");

        // verified 满配 / unverified 三键（tool/args/asOf/asOfKind/raw 整键缺席）
        assertThat(anchorOfState(payload, "verified").fieldNames()).toIterable()
                .containsExactly("snippet", "occ", "state", "tool", "args", "asOf", "asOfKind", "raw");
        JsonNode unverified = anchorOfState(payload, "unverified");
        assertThat(unverified.fieldNames()).toIterable().containsExactly("snippet", "occ", "state");
        assertThat(nullPaths(encodeAndParse(events.get(0)), "$")).isEmpty();
    }

    // ———— 帧级双保险：encode() 出的整帧文本（含 SSE 包装）无 ":null" ————

    @DisplayName("整帧文本（encode SSE 帧）：满配两事件均不含 :null 形态")
    @Test
    void givenFullFeaturedTurn_whenEncodeFrames_thenNoNullLiteralInFrameText() {
        for (CustomEvent event : runFullFeaturedTurn()) {
            assertThat(encoder.encode(toAguiCustom(event)))
                    .doesNotContain(":null")
                    .doesNotContain("[null");
        }
    }

    // ———— 场景与驱动 ————

    /**
     * 满配回合（一次驱动，多测试复用）：真值池含内置行情（陈旧 asOf，触发 stale_quotes）、
     * MCP 调用（CALL 配源）、失败调用（tool_failures）；文本含 verified（1520.33 元）、
     * sourced（19000 亿）、大偏差（15.20 元 → 修正 + correction 域）、四个 unverified
     * （触发 unverified_ratio）、自声明标记 + 词表命中（advice by=both）。工具参数显式携带
     * null 值与嵌套 null（args.market / args.meta.region）——线上深层剥除的真实样本。
     */
    private static List<CustomEvent> runFullFeaturedTurn() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("region", null);
        nested.put("src", "east");
        Map<String, Object> quoteArgs = new LinkedHashMap<>();
        quoteArgs.put("code", "600519");
        quoteArgs.put("market", null);
        quoteArgs.put("meta", nested);
        return runTurn(List.of(
                new ToolInvocation("get_quote", quoteArgs,
                        "{\"price\":1520.33,\"time\":\"2026-10-01 09:30:00\"}", List.of(),
                        "2026-10-01 09:30:00", ToolInvocation.AsOfKind.DATA, false, false, 0L),
                new ToolInvocation("tushare_search", Map.of("keyword", "贵州茅台"),
                        "贵州茅台2025年营收19000亿元", List.of(),
                        "2026-10-06 09:00:00", ToolInvocation.AsOfKind.CALL, false, true, 0L),
                new ToolInvocation("get_kline", Map.of("code", "600519"), "{\"error\":\"timeout\"}",
                        List.of(), "2026-10-06 09:00:00", ToolInvocation.AsOfKind.DATA, true, false, 0L)),
                "贵州茅台现价1520.33元，市盈率25.5倍。机构测算营收19000亿元，错报口径为15.20元。"
                        + "另估产能3.5万吨、员工1.2万人、门店2.1万个。操作上可逢低加仓。\n<!--advice-->");
    }

    /** 驱动真实 hook（固定时钟 processor + rc 真值池 + 线上 messageId），捕获发射的 CustomEvent。 */
    private static List<CustomEvent> runTurn(List<ToolInvocation> pool, String text) {
        TrustAgentHook hook = new TrustAgentHook(
                new TrustTurnProcessor(new InvestProperties().getTrust(), CLOCK));
        RuntimeContext rc = RuntimeContext.builder().userId("1").sessionId("s1").build();
        hook.setRuntimeContext(rc);
        rc.put(TrustWireMessageIdMiddleware.RC_KEY, "wire-msg-9");
        for (ToolInvocation invocation : pool) {
            TrustContext.current(rc).record(invocation);
        }
        List<CustomEvent> seen = new ArrayList<>();
        PostReasoningEvent event = new PostReasoningEvent(
                TrustAgentHookTest.STUB_AGENT, "model", null, Msg.builder()
                        .id("m-1").name("invest").role(MsgRole.ASSISTANT)
                        .textContent(text).usage(new ChatUsage(10, 5, 0.01)).build());
        hook.onEvent(event)
                .contextWrite(ctx -> ctx.put(io.agentscope.core.event.AgentEventEmitter.CONTEXT_KEY,
                        (io.agentscope.core.event.AgentEventEmitter) e -> {
                            if (e instanceof CustomEvent c) {
                                seen.add(c);
                            }
                        }))
                .block();
        return seen;
    }

    /** CustomEvent → AguiEvent$Custom（与库内 CustomAgentEventConverter 四参透传同构）→ 线上编码 → JSON 树。 */
    private JsonNode encodeAndParse(CustomEvent event) throws Exception {
        return TREE.readTree(encodeJson(toAguiCustom(event)));
    }

    private static AguiEvent.Custom toAguiCustom(CustomEvent event) {
        return new AguiEvent.Custom("t-1", "r-1", event.getName(), event.getValue());
    }

    /** 线上编码取 JSON 半边（encode 帧为 SSE 包装，剥前缀取 JSON 体）。 */
    private String encodeJson(AguiEvent.Custom event) {
        String frame = encoder.encode(event);
        return frame.substring(frame.indexOf('{'), frame.lastIndexOf('}') + 1);
    }

    /** 递归收集 null 路径：对象 null 字段（path.key）与数组 null 元素（path[i]）。 */
    private static List<String> nullPaths(JsonNode node, String path) {
        List<String> paths = new ArrayList<>();
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (entry.getValue().isNull()) {
                    paths.add(path + "." + entry.getKey());
                } else {
                    paths.addAll(nullPaths(entry.getValue(), path + "." + entry.getKey()));
                }
            });
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                JsonNode child = node.get(i);
                if (child.isNull()) {
                    paths.add(path + "[" + i + "]");
                } else {
                    paths.addAll(nullPaths(child, path + "[" + i + "]"));
                }
            }
        }
        return paths;
    }

    private static JsonNode anchorOfState(JsonNode payload, String state) {
        for (JsonNode anchor : payload.get("anchors")) {
            if (state.equals(anchor.get("state").asText())) {
                return anchor;
            }
        }
        throw new AssertionError("payload 中不存在 state=" + state + " 的锚定: " + payload);
    }
}
