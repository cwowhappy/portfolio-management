package com.portfolio.invest.agent.trust;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.config.InvestProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TrustTurnProcessor 单元测试（MS-29 B5，设计规格 §4.2 九步流水中的纯函数半边）：
 * 豁免优先序（决策 #16：先配真值 → 用户来源豁免 → 其余 unverified）、跨轮历史池回溯（决策 #15）、
 * 池摘要（§2.2 metadata 落盘形态）、payload v1 形态（§2.1 无 null 用缺键）、修正文本与注记（B2 契约）。
 */
class TrustTurnProcessorTest {

    private final TrustTurnProcessor processor =
            new TrustTurnProcessor(new InvestProperties().getTrust());

    private static ToolInvocation invocation(String resultText, String... specs) {
        return new ToolInvocation("get_quote", Map.of("code", "600519"), resultText,
                List.of(specs), "2026-10-05 14:59:32", ToolInvocation.AsOfKind.DATA, false);
    }

    private static Map<String, Object> historyEntry(String value, String tool, String asOf, String kind) {
        return Map.of("v", value, "tool", tool, "asOf", asOf, "kind", kind);
    }

    // ———— 豁免优先序（契约：先配真值 verified/sourced → 无匹配且用户来源 → 豁免不锚定 → 其余 unverified） ————

    @DisplayName("真值匹配优先：同值既有真值又有用户来源 → verified 不豁免")
    @Test
    void givenValueInBothPoolAndUserText_whenProcess_thenVerifiedWinsOverExemption() {
        var report = processor.process(
                "现价1520.33元。",
                List.of(invocation("{\"code\":\"600519\",\"price\":1520.33,\"time\":\"2026-10-05 14:59:32\"}")),
                List.of(),
                List.of("我关注的价位是1520.33元"));

        assertThat(report.anchors()).hasSize(1);
        assertThat(report.anchors().get(0).state()).isEqualTo(TrustVerdict.VERIFIED);
        assertThat(report.exempted()).isZero();
        assertThat(report.stats().verified()).isEqualTo(1);
    }

    @DisplayName("用户豁免：无真值匹配且值来自近期 user 消息 → 不锚定不计比例")
    @Test
    void givenUserSourcedValueWithoutTruth_whenProcess_thenExemptedNotAnchored() {
        var report = processor.process(
                "股价在1800.5元附近波动。",
                List.of(),
                List.of(),
                List.of("我的成本价是1800.5元"));

        assertThat(report.anchors()).isEmpty();
        assertThat(report.exempted()).isEqualTo(1);
        assertThat(report.stats().verified()).isZero();
        assertThat(report.stats().sourced()).isZero();
        assertThat(report.stats().unverified()).isZero();
    }

    @DisplayName("其余 unverified：无真值无用户来源 → 计入比例")
    @Test
    void givenUnmatchedValue_whenProcess_thenUnverifiedAnchor() {
        var report = processor.process("涨幅3.2%。", List.of(), List.of(), List.of());

        assertThat(report.anchors()).hasSize(1);
        assertThat(report.anchors().get(0).state()).isEqualTo(TrustVerdict.UNVERIFIED);
        assertThat(report.stats().unverified()).isEqualTo(1);
        assertThat(report.exempted()).isZero();
    }

    // ———— 跨轮数值池（§2.2：本回合池 → 历史 Msg metadata；历史被清 → unverified） ————

    @DisplayName("跨轮回溯：本回合无工具，历史池配对 verified 且带原始 asOf")
    @Test
    void givenHistoryPoolEntry_whenProcess_thenVerifiedWithOriginalAsOf() {
        var report = processor.process(
                "现价1520.33元。",
                List.of(),
                List.of(historyEntry("1520.33", "get_quote", "2026-10-05 14:59:32", "data")),
                List.of());

        assertThat(report.anchors()).hasSize(1);
        var anchor = report.anchors().get(0);
        assertThat(anchor.state()).isEqualTo(TrustVerdict.VERIFIED);
        assertThat(anchor.tool()).isEqualTo("get_quote");
        assertThat(anchor.asOf()).isEqualTo("2026-10-05 14:59:32");
        assertThat(anchor.raw()).isEqualTo("1520.33");
    }

    @DisplayName("历史被清（compaction 后）：无历史无本回合真值 → 降级 unverified")
    @Test
    void givenNoHistoryNoTruth_whenProcess_thenDegradesToUnverified() {
        var report = processor.process("现价1520.33元。", List.of(), List.of(), List.of());

        assertThat(report.anchors()).hasSize(1);
        assertThat(report.anchors().get(0).state()).isEqualTo(TrustVerdict.UNVERIFIED);
        assertThat(report.anchors().get(0).tool()).isNull();
    }

    // ———— 真值池构建（resultText + emittedSpecs 提数） ————

    @DisplayName("emittedSpecs 参与比对池：spec JSON 数字可配对 verified")
    @Test
    void givenValueOnlyInEmittedSpec_whenProcess_thenVerifiedFromSpec() {
        var report = processor.process(
                "收盘点位3245.10点。",
                List.of(invocation("查询成功", "{\"type\":\"line\",\"data\":[3245.10]}")),
                List.of(),
                List.of());

        assertThat(report.anchors()).hasSize(1);
        assertThat(report.anchors().get(0).state()).isEqualTo(TrustVerdict.VERIFIED);
        assertThat(report.anchors().get(0).raw()).isEqualTo("3245.10");
    }

    @DisplayName("失败调用不产生可比真值：值落 unverified")
    @Test
    void givenFailedInvocationOnly_whenProcess_thenUnverified() {
        var failed = new ToolInvocation("get_quote", Map.of("code", "600519"),
                "{\"price\":1520.33}", List.of(), null, ToolInvocation.AsOfKind.CALL, true);
        var report = processor.process("现价1520.33元。", List.of(failed), List.of(), List.of());

        assertThat(report.anchors()).hasSize(1);
        assertThat(report.anchors().get(0).state()).isEqualTo(TrustVerdict.UNVERIFIED);
    }

    // ———— 大偏差修正（B2 契约形态） ————

    @DisplayName("大偏差：确定性替换 + 注记行（B2 fixture 08 期望形态）")
    @Test
    void givenMagnitudeError_whenProcess_thenCorrectedTextWithNote() {
        var report = processor.process(
                "现价15.20元",
                List.of(invocation("{\"price\":1520.33,\"time\":\"2026-10-05 14:59:32\"}")),
                List.of(),
                List.of());

        assertThat(report.correctedText())
                .isEqualTo("现价1520.33元\n> ⚠ 校验修正：原文误述 15.20元");
        assertThat(report.rewritten()).isTrue();
        assertThat(report.corrections()).hasSize(1);
        var correction = report.corrections().get(0);
        assertThat(correction.snippet()).isEqualTo("15.20元");
        assertThat(correction.occ()).isEqualTo(1);
        assertThat(correction.replacement()).isEqualTo("1520.33元");
        assertThat(correction.note()).isEqualTo("原文误述 15.20元");
        assertThat(correction.degraded()).isFalse();
        assertThat(report.correctionNotes()).containsExactly("原文误述 15.20元");
    }

    // ———— 池摘要（§2.2 metadata 形态） ————

    @DisplayName("池摘要：本回合真值 {v,tool,asOf,kind}，failed 不入池，值去重")
    @Test
    void givenCurrentPool_whenProcess_thenPoolSummaryBuilt() {
        var report = processor.process(
                "无数字。",
                List.of(
                        invocation("{\"price\":1520.33,\"time\":\"2026-10-05 14:59:32\"}"),
                        invocation("{\"price\":1520.33}", "{\"type\":\"bar\",\"data\":[1520.33]}"),
                        new ToolInvocation("bad_tool", Map.of(), "{\"price\":1.00}", List.of(),
                                null, ToolInvocation.AsOfKind.CALL, true)),
                List.of(),
                List.of());

        assertThat(report.poolSummary()).hasSize(1);
        var entry = report.poolSummary().get(0);
        assertThat(entry.get("v")).isEqualTo("1520.33");
        assertThat(entry.get("tool")).isEqualTo("get_quote");
        assertThat(entry.get("asOf")).isEqualTo("2026-10-05 14:59:32");
        assertThat(entry.get("kind")).isEqualTo("data");
    }

    @DisplayName("池摘要上限：至多 40 条（防 metadata 膨胀）")
    @Test
    void givenManyPoolValues_whenProcess_thenCappedAtForty() {
        List<ToolInvocation> pool = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            pool.add(new ToolInvocation("get_quote", Map.of(), "{\"price\":" + (1000 + i) + ".00}",
                    List.of(), "2026-10-05 14:59:32", ToolInvocation.AsOfKind.DATA, false));
        }
        var report = processor.process("无数字。", pool, List.of(), List.of());

        assertThat(report.poolSummary()).hasSize(40);
    }

    // ———— payload v1 形态（§2.1：无 null 用缺键；correction 键仅有注记时存在） ————

    @DisplayName("payload：v=1、anchors/stats 齐备、correction 键仅有修正时存在、无 null 值")
    @Test
    void givenCorrectionHappened_whenToPayload_thenShapeHolds() {
        var report = processor.process(
                "现价15.20元，成本1800.5元。",
                List.of(invocation("{\"price\":1520.33,\"time\":\"2026-10-05 14:59:32\"}")),
                List.of(),
                List.of("我的成本价是1800.5元"));

        Map<String, Object> payload = report.toPayload();

        assertThat(payload.get("v")).isEqualTo(1);
        assertThat(payload).containsKey("anchors").containsKey("stats");
        assertThat(payload).containsKey("correction");
        @SuppressWarnings("unchecked")
        Map<String, Object> correction = (Map<String, Object>) payload.get("correction");
        assertThat(correction.get("notes")).asList().containsExactly("原文误述 15.20元");
        assertNoNullValues(payload, "$");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> anchors = (List<Map<String, Object>>) payload.get("anchors");
        assertThat(anchors).hasSize(1);
        assertThat(anchors.get(0))
                .containsEntry("state", "verified")
                .containsEntry("tool", "get_quote")
                .containsEntry("asOf", "2026-10-05 14:59:32")
                .containsEntry("asOfKind", "data")
                .containsEntry("raw", "1520.33");
        assertThat(anchors.get(0)).containsKey("args");
    }

    @DisplayName("payload：unverified anchor 缺省来源键（tool/asOf/asOfKind/raw 缺键）")
    @Test
    void givenUnverifiedOnly_whenToPayload_thenAnchorOmitsSourceKeys() {
        var report = processor.process("涨幅3.2%。", List.of(), List.of(), List.of());

        Map<String, Object> payload = report.toPayload();

        assertThat(payload).doesNotContainKey("correction");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> anchors = (List<Map<String, Object>>) payload.get("anchors");
        assertThat(anchors).hasSize(1);
        assertThat(anchors.get(0))
                .containsOnlyKeys("snippet", "occ", "state")
                .containsEntry("state", "unverified")
                .containsEntry("snippet", "3.2%")
                .containsEntry("occ", 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> stats = (Map<String, Object>) payload.get("stats");
        assertThat(stats).containsEntry("unverified", 1);
    }

    @DisplayName("payload：asOfKind 线名为小写 data/generated/call（§2.1 语义）")
    @Test
    void givenGeneratedKind_whenToPayload_thenLowercaseWireName() {
        var generated = new ToolInvocation("get_market_overview", Map.of(), "{\"price\":1520.33}",
                List.of(), "2026-10-05 15:00:00", ToolInvocation.AsOfKind.GENERATED, false);
        var report = processor.process("现价1520.33元。", List.of(generated), List.of(), List.of());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> anchors = (List<Map<String, Object>>) report.toPayload().get("anchors");
        assertThat(anchors.get(0)).containsEntry("asOfKind", "generated");
    }

    // ———— 护栏（null 入参直通） ————

    @DisplayName("护栏：null/空文本直通，零锚定零修正")
    @Test
    void givenNullText_whenProcess_thenPassthrough() {
        var report = processor.process(null, List.of(), List.of(), List.of());

        assertThat(report.originalText()).isNull();
        assertThat(report.correctedText()).isNull();
        assertThat(report.anchors()).isEmpty();
        assertThat(report.corrections()).isEmpty();
    }

    // ———— 断言辅助 ————

    private static void assertNoNullValues(Object node, String path) {
        if (node instanceof Map<?, ?> map) {
            for (var e : map.entrySet()) {
                assertThat(e.getValue()).as("payload %s.%s 不得为 null", path, e.getKey()).isNotNull();
                assertNoNullValues(e.getValue(), path + "." + e.getKey());
            }
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                assertThat(list.get(i)).as("payload %s[%d] 不得为 null", path, i).isNotNull();
                assertNoNullValues(list.get(i), path + "[" + i + "]");
            }
        }
    }
}
