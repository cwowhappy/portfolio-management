package com.portfolio.invest.agent.trust;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.config.InvestProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
                List.of(specs), "2026-10-05 14:59:32", ToolInvocation.AsOfKind.DATA, false, false);
    }

    private static Map<String, Object> historyEntry(String value, String tool, String asOf, String kind) {
        return Map.of("v", value, "tool", tool, "asOf", asOf, "kind", kind);
    }

    /** 历史池条目（带 mcp 键的新格式，终审 B3-① 分桶迁移）。 */
    private static Map<String, Object> historyEntry(
            String value, String tool, String asOf, String kind, boolean mcp) {
        Map<String, Object> entry = new LinkedHashMap<>(historyEntry(value, tool, asOf, kind));
        entry.put("mcp", mcp);
        return entry;
    }

    /**
     * research_draft 真实返回形态（决策 #6 排除清单锁定）：中文摘要 + ```research-draft 围栏 JSON
     * ——整体非纯 JSON（readTree 失败），修复前靠该格式巧合落 CALL 兜底进 MCP 精确配源池。
     */
    private static ToolInvocation researchDraftEcho() {
        return new ToolInvocation("research_draft", Map.of("stage", "STRATEGY"),
                "草稿已记录：STRATEGY 阶段共 3 条要点。\n```research-draft\n"
                        + "{\"valuationLow\":12.5,\"valuationHigh\":18.2}\n```",
                List.of(), "2026-10-06 10:00:00", ToolInvocation.AsOfKind.CALL, false, false);
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
                "{\"price\":1520.33}", List.of(), null, ToolInvocation.AsOfKind.CALL, true, false);
        var report = processor.process("现价1520.33元。", List.of(failed), List.of(), List.of());

        assertThat(report.anchors()).hasSize(1);
        assertThat(report.anchors().get(0).state()).isEqualTo(TrustVerdict.UNVERIFIED);
    }

    // ———— 排除清单（需求决策 #6 定稿，终审 Important）：research_draft 回显非真值 ————

    @DisplayName("排除清单：research_draft 回显数字正文引用 → unverified（非 sourced/verified，不压制信号）")
    @Test
    void givenResearchDraftEchoOnly_whenProcess_thenUnverifiedNotSourced() {
        var report = processor.process("估值下沿 12.5。",
                List.of(researchDraftEcho()), List.of(), List.of());

        assertThat(report.anchors()).hasSize(1);
        var anchor = report.anchors().get(0);
        assertThat(anchor.state()).as("回显数字不得配源（模型自产数据，无独立真值）")
                .isEqualTo(TrustVerdict.UNVERIFIED);
        assertThat(anchor.tool()).isNull();
        assertThat(report.stats()).isEqualTo(new AnchorBatch.Stats(0, 0, 1));
    }

    @DisplayName("排除清单：返回长出可解析时点（纯 JSON DATA 形态）仍不入池 → unverified")
    @Test
    void givenResearchDraftJsonLikeReturn_whenProcess_thenStillUnverified() {
        var jsonLike = new ToolInvocation("research_draft", Map.of(),
                "{\"valuationLow\":12.5,\"time\":\"2026-10-06 10:00:00\"}", List.of(),
                "2026-10-06 10:00:00", ToolInvocation.AsOfKind.DATA, false, false);
        var report = processor.process("估值下沿 12.5。", List.of(jsonLike), List.of(), List.of());

        assertThat(report.anchors().get(0).state()).isEqualTo(TrustVerdict.UNVERIFIED);
    }

    @DisplayName("排除清单：升级前已落库的历史池条目（tool=research_draft）同样拦截 → unverified")
    @Test
    void givenExcludedToolHistoryEntry_whenProcess_thenStillUnverified() {
        var report = processor.process("估值下沿 12.5。", List.of(),
                List.of(historyEntry("12.5", "research_draft", "2026-10-06 10:00:00", "data")),
                List.of());

        assertThat(report.anchors().get(0).state()).isEqualTo(TrustVerdict.UNVERIFIED);
    }

    @DisplayName("池摘要：排除工具不落摘要（决策 #6）；mcp 标志随行落键")
    @Test
    void givenMixedPoolWithExcludedTool_whenProcess_thenSummarySkipsExcludedAndCarriesMcp() {
        var mcpCall = new ToolInvocation("tushare_search", Map.of(), "营收 19000亿", List.of(),
                "2026-10-06 09:00:00", ToolInvocation.AsOfKind.CALL, false, true);
        var report = processor.process("无数字。",
                List.of(researchDraftEcho(), mcpCall,
                        invocation("{\"price\":1520.33,\"time\":\"2026-10-05 14:59:32\"}")),
                List.of(), List.of());

        assertThat(report.poolSummary()).extracting(entry -> entry.get("tool"))
                .containsExactlyInAnyOrder("get_quote", "tushare_search")
                .doesNotContain("research_draft");
        for (Map<String, Object> entry : report.poolSummary()) {
            assertThat(entry.get("mcp"))
                    .as("tool=%s 的 mcp 键随行", entry.get("tool"))
                    .isEqualTo("tushare_search".equals(entry.get("tool")));
        }
    }

    // ———— 池分桶迁移（终审 B3-①）：mcp 字面标志承载，内置 CALL 与 MCP 不再同桶 ————

    @DisplayName("历史池 mcp 回读：新格式内置 CALL+mcp=false 进比对池同值 verified；MCP+mcp=true 精确配源 sourced")
    @Test
    void givenHistoryEntriesWithMcpKey_whenProcess_thenBucketedByFlag() {
        var builtinCall = processor.process("下沿 12.5。", List.of(),
                List.of(historyEntry("12.5", "get_quote", "2026-10-06 10:00:00", "call", false)),
                List.of());
        assertThat(builtinCall.anchors().get(0).state())
                .as("内置无时点工具（CALL 兜底）真值可参与比对")
                .isEqualTo(TrustVerdict.VERIFIED);

        var mcpNew = processor.process("下沿 12.5。", List.of(),
                List.of(historyEntry("12.5", "tushare_search", "2026-10-06 10:00:00", "call", true)),
                List.of());
        assertThat(mcpNew.anchors().get(0).state())
                .as("MCP 真值精确配源，不产 verified（决策 #5）")
                .isEqualTo(TrustVerdict.SOURCED);
    }

    @DisplayName("历史池旧格式兼容：缺 mcp 键回退 kind==call（与升级前分桶严格等价）→ sourced")
    @Test
    void givenLegacyHistoryEntryWithoutMcpKey_whenProcess_thenFallbackCallKindBucketsAsMcp() {
        var report = processor.process("下沿 12.5。", List.of(),
                List.of(historyEntry("12.5", "tushare_search", "2026-10-06 10:00:00", "call")),
                List.of());

        assertThat(report.anchors().get(0).state()).isEqualTo(TrustVerdict.SOURCED);
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
                                null, ToolInvocation.AsOfKind.CALL, true, false)),
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
                    List.of(), "2026-10-05 14:59:32", ToolInvocation.AsOfKind.DATA, false, false));
        }
        var report = processor.process("无数字。", pool, List.of(), List.of());

        assertThat(report.poolSummary()).hasSize(40);
    }

    // ———— 覆盖序（MS-29 后续②）：更新既有键移到尾部，40-cap 逐出 = 最久未更新 ————

    @DisplayName("池摘要覆盖序：旧键更新后超容量，逐出的是更久未更新的键而非早插入且刚更新的键")
    @Test
    void givenUpdatedOldKeyOverCapacity_whenProcess_thenEvictsLeastRecentlyUpdated() {
        List<ToolInvocation> pool = new ArrayList<>();
        pool.add(invocation("{\"price\":100.00}"));
        for (int i = 1; i <= 40; i++) {
            pool.add(new ToolInvocation("get_quote", Map.of(), "{\"price\":" + (100 + i) + ".00}",
                    List.of(), "2026-10-05 14:59:32", ToolInvocation.AsOfKind.DATA, false, false));
        }
        // 同值再调用一次（「后写覆盖=最近一次调用」语义）：键 100 刚被更新
        pool.add(invocation("{\"price\":100.00,\"time\":\"2026-10-06 09:30:00\"}"));
        var report = processor.process("无数字。", pool, List.of(), List.of());

        // 41 个不同值、上限 40：被逐出的应是最久未更新的键（101），而非更早插入但刚更新的键（100）
        assertThat(report.poolSummary()).hasSize(40);
        assertThat(report.poolSummary())
                .extracting(entry -> entry.get("v"))
                .doesNotContain("101")
                .contains("100");
        // 刚更新的键移到尾部（= 最近条目位）
        assertThat(report.poolSummary().get(39).get("v")).isEqualTo("100");
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
                List.of(), "2026-10-05 15:00:00", ToolInvocation.AsOfKind.GENERATED, false, false);
        var report = processor.process("现价1520.33元。", List.of(generated), List.of(), List.of());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> anchors = (List<Map<String, Object>>) report.toPayload().get("anchors");
        assertThat(anchors.get(0)).containsEntry("asOfKind", "generated");
    }

    // ———— advice 半边（B6：自声明标记 + 词表兜底井集；payload §2.1 缺键表达无建议） ————

    @DisplayName("标记剥离先于校验改写：注记行附加在干净文本上，originalText 保留原始标记")
    @Test
    void givenMarkerAndMagnitudeError_whenProcess_thenNoteAppendedOnCleanText() {
        var report = processor.process(
                "现价15.20元\n<!--advice-->",
                List.of(invocation("{\"price\":1520.33,\"time\":\"2026-10-05 14:59:32\"}")),
                List.of(),
                List.of());

        assertThat(report.originalText()).isEqualTo("现价15.20元\n<!--advice-->");
        assertThat(report.correctedText())
                .isEqualTo("现价1520.33元\n> ⚠ 校验修正：原文误述 15.20元");
        assertThat(report.rewritten()).isTrue();
        assertThat(report.advice().flag()).isTrue();
        assertThat(report.advice().by()).isEqualTo("self");
    }

    @DisplayName("payload.advice：标记+词表都命中 → {flag,by,text} 全携带（by=both）")
    @Test
    void givenMarkerAndLexiconHit_whenToPayload_thenAdviceCarriedWithText() {
        var report = processor.process(
                "估值偏低，建议分批建仓。\n<!--advice-->", List.of(), List.of(), List.of());

        Map<String, Object> payload = report.toPayload();

        assertThat(payload).containsKey("advice");
        @SuppressWarnings("unchecked")
        Map<String, Object> advice = (Map<String, Object>) payload.get("advice");
        assertThat(advice)
                .containsEntry("flag", true)
                .containsEntry("by", "both")
                .containsEntry("text",
                        "以上内容由 AI 生成，仅供参考，不构成任何投资建议；"
                                + "市场有风险，投资决策请独立判断或咨询持牌专业机构。");
        assertNoNullValues(payload, "$");
        assertThat(report.correctedText()).isEqualTo("估值偏低，建议分批建仓。");
        assertThat(report.rewritten()).isTrue();
    }

    @DisplayName("payload.advice：仅词表命中 → by=lexicon（文案随行）")
    @Test
    void givenLexiconHitOnly_whenToPayload_thenAdviceByLexicon() {
        var report = processor.process("高位震荡，注意设置止损位。", List.of(), List.of(), List.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> advice = (Map<String, Object>) report.toPayload().get("advice");

        assertThat(advice).containsEntry("flag", true).containsEntry("by", "lexicon");
        assertThat(advice).containsKey("text");
    }

    @DisplayName("payload.advice 缺键：无标记且词表未命中（含否定豁免）→ 无 advice 键")
    @Test
    void givenNoAdviceOrNegatedLexicon_whenToPayload_thenAdviceKeyAbsent() {
        var negated = processor.process("以上内容不构成买入建议。", List.of(), List.of(), List.of());
        assertThat(negated.toPayload()).doesNotContainKey("advice");
        assertThat(negated.rewritten()).isFalse();

        var plain = processor.process("今日大盘上涨。", List.of(), List.of(), List.of());
        assertThat(plain.toPayload()).doesNotContainKey("advice");
    }

    // ———— confidence 半边（B7：四类机制信号接线 + payload.confidence 键形态/缺键） ————

    /** 固定「今日」= 2026-10-06 的处理器（陈旧度判定可确定化，禁真实 sleep）。 */
    private static TrustTurnProcessor fixedClockProcessor() {
        return new TrustTurnProcessor(new InvestProperties().getTrust(),
                java.time.Clock.fixed(java.time.Instant.parse("2026-10-06T04:00:00Z"),
                        java.time.ZoneId.of("Asia/Shanghai")));
    }

    @DisplayName("payload.confidence 缺键：无信号命中 → 键缺省（v1 无加权组合，无命中不携带）")
    @Test
    void givenNoSignalHit_whenToPayload_thenConfidenceKeyAbsent() {
        var report = fixedClockProcessor().process("今日大盘上涨。", List.of(), List.of(), List.of());

        assertThat(report.confidence()).isEmpty();
        assertThat(report.toPayload()).doesNotContainKey("confidence");
    }

    @DisplayName("接线·未溯源比例：3/4 unverified → confidence.signals=[unverified_ratio:0.75]")
    @Test
    void givenThreeOfFourUnverified_whenProcess_thenUnverifiedRatioInSignals() {
        var report = fixedClockProcessor().process(
                "现价1520.33元，涨幅3.2%，换手1.5%，量比2.8%。",
                List.of(invocation("{\"price\":1520.33,\"time\":\"2026-10-06 09:30:00\"}")),
                List.of(),
                List.of());

        assertThat(report.stats().unverified()).isEqualTo(3);
        Map<String, Object> payload = report.toPayload();
        assertThat(payload).containsKey("confidence");
        @SuppressWarnings("unchecked")
        Map<String, Object> confidence = (Map<String, Object>) payload.get("confidence");
        assertThat(confidence.get("signals")).asList()
                .containsExactly("unverified_ratio:0.75");
        assertNoNullValues(payload, "$");
    }

    @DisplayName("接线·豁免口径：用户来源数字不入比例分子分母（2 unverified < 3 不触发）")
    @Test
    void givenUserExemptedValue_whenProcess_thenExemptedNotCountedInRatio() {
        // 1800.5 豁免（用户来源），仅 2 个 unverified → 不触发；若豁免计入则 3/3 必触发
        var report = fixedClockProcessor().process(
                "成本1800.5元，涨幅3.2%，换手1.5%。",
                List.of(),
                List.of(),
                List.of("我的成本价是1800.5元"));

        assertThat(report.exempted()).isEqualTo(1);
        assertThat(report.confidence()).isEmpty();
        assertThat(report.toPayload()).doesNotContainKey("confidence");
    }

    @DisplayName("接线·行情陈旧：quote 真值 asOf 2026-09-01（35 自然日）→ stale_quotes:1")
    @Test
    void givenStaleQuoteTruth_whenProcess_thenStaleQuotesInSignals() {
        // 锚定携带 invocation.asOf（B3 记录字段）——JSON time 只影响装饰器解析，此处直构陈旧时点
        var stale = new ToolInvocation("get_quote", Map.of("code", "600519"),
                "{\"price\":1520.33,\"time\":\"2026-09-01 09:30:00\"}", List.of(),
                "2026-09-01 09:30:00", ToolInvocation.AsOfKind.DATA, false, false);
        var report = fixedClockProcessor().process(
                "现价1520.33元。",
                List.of(stale),
                List.of(),
                List.of());

        assertThat(report.confidence())
                .containsExactly(new ConfidenceSignal.Hit(ConfidenceSignal.STALE_QUOTES, 1));
        @SuppressWarnings("unchecked")
        Map<String, Object> confidence =
                (Map<String, Object>) report.toPayload().get("confidence");
        assertThat(confidence.get("signals")).asList().containsExactly("stale_quotes:1");
    }

    @DisplayName("接线·GENERATED 锚定不参与陈旧度：overview 生成时刻陈旧 → 无 stale 信号")
    @Test
    void givenGeneratedAsOfAnchor_whenProcess_thenNoStaleSignal() {
        var generated = new ToolInvocation("get_market_overview", Map.of(), "{\"close\":3245.10}",
                List.of(), "2026-01-01 12:00:00", ToolInvocation.AsOfKind.GENERATED, false, false);
        var report = fixedClockProcessor().process(
                "收盘点位3245.10点。", List.of(generated), List.of(), List.of());

        assertThat(report.confidence()).isEmpty();
        assertThat(report.toPayload()).doesNotContainKey("confidence");
    }

    @DisplayName("接线·工具失败：真值池 1 次 failed=true → tool_failures:1")
    @Test
    void givenFailedInvocation_whenProcess_thenToolFailuresInSignals() {
        var failed = new ToolInvocation("get_quote", Map.of("code", "600519"),
                "{\"price\":1520.33}", List.of(), null, ToolInvocation.AsOfKind.CALL, true, false);
        var report = fixedClockProcessor().process(
                "涨幅3.2%。", List.of(failed), List.of(), List.of());

        assertThat(report.confidence())
                .containsExactly(new ConfidenceSignal.Hit(ConfidenceSignal.TOOL_FAILURES, 1));
    }

    @DisplayName("接线·修正：1 次大偏差替换 → corrections:1 随 payload 透传")
    @Test
    void givenCorrectionHappened_whenProcess_thenCorrectionsInSignals() {
        var report = fixedClockProcessor().process(
                "现价15.20元",
                List.of(invocation("{\"price\":1520.33,\"time\":\"2026-10-06 09:30:00\"}")),
                List.of(),
                List.of());

        assertThat(report.confidence())
                .containsExactly(new ConfidenceSignal.Hit(ConfidenceSignal.CORRECTIONS, 1));
        @SuppressWarnings("unchecked")
        Map<String, Object> confidence =
                (Map<String, Object>) report.toPayload().get("confidence");
        assertThat(confidence.get("signals")).asList().containsExactly("corrections:1");
    }

    @DisplayName("接线·null 文本直通：confidence 空且 payload 无键")
    @Test
    void givenNullText_whenProcess_thenConfidenceEmpty() {
        var report = fixedClockProcessor().process(null, List.of(), List.of(), List.of());

        assertThat(report.confidence()).isEmpty();
        assertThat(report.toPayload()).doesNotContainKey("confidence");
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
