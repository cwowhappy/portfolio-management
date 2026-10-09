package com.portfolio.invest.agent.trust;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.config.InvestProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ConsistencyValidator 单测（MS-29 B2，设计规格 §三/§4.2 步骤 3~4）：
 * 三态判定（容差参数组①）+ 确定性替换（注记行/单位风格回写）+ 重试上限降级出口。
 */
class ConsistencyValidatorTest {

    private final ConsistencyValidator validator =
            new ConsistencyValidator(new InvestProperties().getTrust());

    @DisplayName("舍入：15.2元 vs 15.23元 相对容差内 → verified")
    @Test
    void givenRoundedValue_whenVerify_thenVerified() {
        List<NumberToken> tokens = NumberExtractor.extract("现价约15.2元");

        AnchorBatch batch = validator.verify(tokens, pool(truth("get_quote", "现价 15.23元")));

        assertThat(batch.anchors()).hasSize(1);
        assertThat(batch.anchors().getFirst().state()).isEqualTo(TrustVerdict.VERIFIED);
        assertThat(batch.anchors().getFirst().raw()).isEqualTo("15.23元");
        assertThat(batch.stats()).isEqualTo(new AnchorBatch.Stats(1, 0, 0));
    }

    @DisplayName("万亿换算：1.9万亿 vs 19000亿 归一后等值 → verified")
    @Test
    void givenWanYiConversion_whenVerify_thenVerified() {
        List<NumberToken> tokens = NumberExtractor.extract("市值1.9万亿");

        AnchorBatch batch = validator.verify(tokens, pool(truth("get_quote", "总市值 19000亿")));

        assertThat(batch.anchors().getFirst().state()).isEqualTo(TrustVerdict.VERIFIED);
    }

    @DisplayName("数量级错：15.20 vs 1520.33 → 拦截改写为工具原值 + 注记行，终态 verified")
    @Test
    void givenMagnitudeError_whenCorrect_thenInterceptedAndRewritten() {
        CorrectionResult result =
                validator.correct("对应市盈率15.20", pool(truth("get_quote", "1520.33")));

        assertThat(result.correctedText())
                .isEqualTo("对应市盈率1520.33\n> ⚠ 校验修正：原文误述 15.20");
        assertThat(result.corrections()).hasSize(1);
        assertThat(result.corrections().getFirst().snippet()).isEqualTo("15.20");
        assertThat(result.corrections().getFirst().occ()).isEqualTo(1);
        assertThat(result.corrections().getFirst().replacement()).isEqualTo("1520.33");
        assertThat(result.corrections().getFirst().note()).isEqualTo("原文误述 15.20");
        assertThat(result.corrections().getFirst().degraded()).isFalse();
        assertThat(result.correctionFailures()).isZero();
        assertThat(result.batch().correctionNotes()).containsExactly("原文误述 15.20");
        assertThat(result.batch().anchors().getFirst().state()).isEqualTo(TrustVerdict.VERIFIED);
        assertThat(result.batch().anchors().getFirst().raw()).isEqualTo("1520.33");
    }

    @DisplayName("无真值：文本数字无工具数据支撑 → unverified（tool/raw 为 null）")
    @Test
    void givenNoTruth_whenVerify_thenUnverified() {
        AnchorBatch batch = validator.verify(NumberExtractor.extract("可看高到1800元"), pool());

        assertThat(batch.anchors()).hasSize(1);
        AnchorRecord anchor = batch.anchors().getFirst();
        assertThat(anchor.state()).isEqualTo(TrustVerdict.UNVERIFIED);
        assertThat(anchor.tool()).isNull();
        assertThat(anchor.raw()).isNull();
        assertThat(batch.stats()).isEqualTo(new AnchorBatch.Stats(0, 0, 1));
    }

    @DisplayName("重试上限：替换后与前邻数字融合仍偏差且重试预算耗尽（max-retries=0）→ 降级，原文保留 + 转显式标注")
    @Test
    void givenReentrantFusionAndNoRetries_whenCorrect_thenDegradeAfterRetries() {
        String text = "区间15-152元";
        CorrectionResult result = zeroRetryValidator().correct(text,
                pool(truth("get_quote", "15元"), truth("get_quote", "152元")));

        assertThat(result.correctedText()).isEqualTo(text);
        assertThat(result.correctionFailures()).isEqualTo(1);
        assertThat(result.corrections()).hasSize(1);
        assertThat(result.corrections().getFirst().degraded()).isTrue();
        assertThat(result.corrections().getFirst().snippet()).isEqualTo("-152元");
        assertThat(result.batch().anchors())
                .extracting(AnchorRecord::state)
                .containsExactly(TrustVerdict.VERIFIED, TrustVerdict.SOURCED);
        assertThat(result.batch().correctionNotes())
                .containsExactly("校验修正失败：原文误述 -152元（重试上限已到，保留原文并转显式标注）");
    }

    @DisplayName("重试生效：同融合场景默认预算（max-retries=2）→ 第二轮替换后收敛，注记一条")
    @Test
    void givenFusionWithDefaultRetries_whenCorrect_thenSecondRoundConverges() {
        CorrectionResult result = validator.correct("区间15-152元",
                pool(truth("get_quote", "15元"), truth("get_quote", "152元")));

        assertThat(result.correctedText()).isEqualTo("区间152元\n> ⚠ 校验修正：原文误述 -152元");
        assertThat(result.correctionFailures()).isZero();
        assertThat(result.corrections()).hasSize(1);
        assertThat(result.corrections().getFirst().degraded()).isFalse();
        assertThat(result.batch().correctionNotes()).containsExactly("原文误述 -152元");
    }

    @DisplayName("容差边界：|10.2 − 10.0| == relative×10 → ≤ 语义 → verified")
    @Test
    void givenExactToleranceBoundary_whenVerify_thenVerified() {
        AnchorBatch batch = validator.verify(NumberExtractor.extract("现价10.2元"),
                pool(truth("get_quote", "10.0元")));

        assertThat(batch.anchors().getFirst().state()).isEqualTo(TrustVerdict.VERIFIED);
    }

    @DisplayName("中间带：容差外但 ≤ deviation（10.6 vs 10.0，偏差 6%）→ 按大偏差拦截（决策 #1）")
    @Test
    void givenMiddleBand_whenCorrect_thenIntercepted() {
        CorrectionResult result =
                validator.correct("现价10.6元", pool(truth("get_quote", "10.0元")));

        assertThat(result.correctedText()).isEqualTo("现价10元\n> ⚠ 校验修正：原文误述 10.6元");
        assertThat(result.corrections()).hasSize(1);
    }

    @DisplayName("价格绝对容差：0.06元 vs 0.05元 相对过小，price=0.01 兜底 → verified")
    @Test
    void givenMicroPrice_whenVerify_thenPriceAbsoluteApplies() {
        AnchorBatch batch = validator.verify(NumberExtractor.extract("手续费0.06元"),
                pool(truth("get_quote", "0.05元")));

        assertThat(batch.anchors().getFirst().state()).isEqualTo(TrustVerdict.VERIFIED);
    }

    @DisplayName("量纲门：裸数字 15.2 不与百分比真值 15.2% 配对 → unverified")
    @Test
    void givenPercentDimensionMismatch_whenVerify_thenUnverified() {
        AnchorBatch batch = validator.verify(NumberExtractor.extract("市盈率15.2"),
                pool(truth("get_quote", "15.2%")));

        assertThat(batch.anchors().getFirst().state()).isEqualTo(TrustVerdict.UNVERIFIED);
    }

    @DisplayName("护栏：tokens/pool 为 null → 空锚定集，不断流")
    @Test
    void givenNullInputs_whenVerify_thenEmptyBatch() {
        AnchorBatch batch = validator.verify(null, null);

        assertThat(batch.anchors()).isEmpty();
        assertThat(batch.stats()).isEqualTo(new AnchorBatch.Stats(0, 0, 0));
        assertThat(batch.correctionNotes()).isEmpty();
    }

    @DisplayName("无偏差：全部 verified 时 correct() 原文直通、零注记")
    @Test
    void givenNoOffenders_whenCorrect_thenUnchanged() {
        CorrectionResult result =
                validator.correct("现价15.23元", pool(truth("get_quote", "15.23元")));

        assertThat(result.correctedText()).isEqualTo("现价15.23元");
        assertThat(result.corrections()).isEmpty();
        assertThat(result.batch().correctionNotes()).isEmpty();
        assertThat(result.correctionFailures()).isZero();
    }

    @DisplayName("MCP 真值：值相等只配源不比对 → sourced 且不触发改写")
    @Test
    void givenMcpValueEqual_whenVerify_thenSourcedNotVerified() {
        List<ToolInvocation> pool = pool(mcpTruth("15.20元"));

        AnchorBatch batch = validator.verify(NumberExtractor.extract("现价15.20元"), pool);
        CorrectionResult result = validator.correct("现价15.20元", pool);

        assertThat(batch.anchors().getFirst().state()).isEqualTo(TrustVerdict.SOURCED);
        assertThat(batch.anchors().getFirst().asOfKind()).isEqualTo(ToolInvocation.AsOfKind.CALL);
        assertThat(result.correctedText()).isEqualTo("现价15.20元");
        assertThat(result.corrections()).isEmpty();
    }

    @DisplayName("分桶迁移（终审 B3-①）：内置 CALL 兜底真值（mcp=false）进比对池——同值 verified，不再与 MCP 同桶")
    @Test
    void givenBuiltinCallKindTruth_whenVerify_thenComparableVerified() {
        ToolInvocation builtinCall = new ToolInvocation("get_quote", Map.of("code", "600519"),
                "15.20元", List.of(), "2026-10-05 14:59:32",
                ToolInvocation.AsOfKind.CALL, false, false, 0L);

        AnchorBatch batch = validator.verify(NumberExtractor.extract("现价15.20元"), pool(builtinCall));

        assertThat(batch.anchors().getFirst().state())
                .as("mcp 字面标志承载分桶：内置无时点真值可参与比对")
                .isEqualTo(TrustVerdict.VERIFIED);
    }

    @DisplayName("MCP 真值不参与可归因比对：值有偏差（15.2 vs 15.23）→ unverified（非拦截）")
    @Test
    void givenMcpValueWithDeviation_whenVerify_thenUnverifiedNotIntercepted() {
        AnchorBatch batch = validator.verify(NumberExtractor.extract("现价15.2元"), pool(mcpTruth("15.23元")));

        assertThat(batch.anchors().getFirst().state())
                .as("MCP 池只做精确相等配源——偏差值不配对不拦截")
                .isEqualTo(TrustVerdict.UNVERIFIED);
    }

    @DisplayName("失败调用：failed=true 不产生可比真值 → unverified")
    @Test
    void givenFailedInvocation_whenVerify_thenUnverified() {
        AnchorBatch batch = validator.verify(NumberExtractor.extract("现价15.23元"),
                pool(failedTruth("15.23元")));

        assertThat(batch.anchors().getFirst().state()).isEqualTo(TrustVerdict.UNVERIFIED);
    }

    @DisplayName("单位风格回写：snippet 1.95万亿（万亿单位）→ 真值 19000亿 按同单位回写 1.9万亿")
    @Test
    void givenUnitSnippet_whenCorrect_thenSameUnitStyleRewrite() {
        CorrectionResult result =
                validator.correct("市值1.95万亿", pool(truth("get_quote", "19000亿")));

        assertThat(result.correctedText()).isEqualTo("市值1.9万亿\n> ⚠ 校验修正：原文误述 1.95万亿");
        assertThat(result.corrections().getFirst().replacement()).isEqualTo("1.9万亿");
    }

    @DisplayName("双偏差同轮修正：两个可归因偏差一轮各替换一次 + 按原文顺序两条注记")
    @Test
    void givenTwoOffenders_whenCorrect_thenBothRewrittenInOneRound() {
        CorrectionResult result = validator.correct("现价1552元，成交1552手",
                pool(truth("get_quote", "1520.33元"), truth("get_quote", "1500手")));

        assertThat(result.correctedText()).isEqualTo(
                "现价1520.33元，成交1500手\n> ⚠ 校验修正：原文误述 1552元\n> ⚠ 校验修正：原文误述 1552手");
        assertThat(result.corrections()).extracting(CorrectionResult.Correction::snippet)
                .containsExactly("1552元", "1552手");
        assertThat(result.correctionFailures()).isZero();
    }

    @DisplayName("非数据性数字：年份/代码不产生锚定（豁免与统计只消费 dataLike）")
    @Test
    void givenNonDataLikeTokens_whenVerify_thenExcludedFromAnchors() {
        AnchorBatch batch = validator.verify(
                NumberExtractor.extract("2026年10月5日，贵州茅台（600519）收于15.2元"),
                pool(truth("get_quote", "15.23元")));

        assertThat(batch.anchors()).hasSize(1);
        assertThat(batch.anchors().getFirst().snippet()).isEqualTo("15.2元");
    }

    @DisplayName("量级跨度护栏：2 与 2.4万亿 同首有效数字但跨 12 个数量级 → 不配对 unverified")
    @Test
    void givenDeviationBeyondSpan_whenVerify_thenUnverified() {
        AnchorBatch batch = validator.verify(NumberExtractor.extract("日产约2片"),
                pool(truth("get_quote", "2.4万亿")));

        assertThat(batch.anchors().getFirst().state()).isEqualTo(TrustVerdict.UNVERIFIED);
    }

    @DisplayName("复合单位回写：snippet 带亿元/万元/万亿元后缀时按复合乘数换算（controller 裁定 1）")
    @Test
    void givenCompositeUnitSnippet_whenWriteBack_thenConvertedInCompositeUnit() {
        assertThat(ConsistencyValidator.writeBack(new BigDecimal("1900000000000"), "19500亿元"))
                .isEqualTo("19000亿元");
        assertThat(ConsistencyValidator.writeBack(new BigDecimal("34800"), "3.4万元"))
                .isEqualTo("3.48万元");
        assertThat(ConsistencyValidator.writeBack(new BigDecimal("1900000000000"), "1.95万亿元"))
                .isEqualTo("1.9万亿元");
    }

    @DisplayName("复合单位端到端：「19500亿元」文本 vs 1.9万亿 真值 → 替换后终文含「19000亿元」且 verified")
    @Test
    void givenCompositeYiYuanText_whenCorrect_thenRewrittenWithYiYuanIntact() {
        CorrectionResult result = validator.correct("贵州茅台总市值19500亿元",
                pool(truth("get_quote", "1.9万亿")));

        assertThat(result.correctedText())
                .isEqualTo("贵州茅台总市值19000亿元\n> ⚠ 校验修正：原文误述 19500亿");
        assertThat(result.corrections().getFirst().replacement()).isEqualTo("19000亿");
        assertThat(result.batch().anchors().getFirst().state()).isEqualTo(TrustVerdict.VERIFIED);
        assertThat(result.batch().anchors().getFirst().raw()).isEqualTo("1.9万亿");
    }

    static ConsistencyValidator zeroRetryValidator() {
        InvestProperties props = new InvestProperties();
        props.setTrust(new InvestProperties.Trust());
        props.getTrust().setCorrection(new InvestProperties.Trust.CorrectionSettings(0));
        return new ConsistencyValidator(props.getTrust());
    }

    static ToolInvocation truth(String tool, String resultText) {
        return new ToolInvocation(tool, Map.of("code", "600519"), resultText, List.of(),
                "2026-10-05 14:59:32", ToolInvocation.AsOfKind.DATA, false, false, 0L);
    }

    static ToolInvocation mcpTruth(String resultText) {
        return new ToolInvocation("mcp_tushare", Map.of(), resultText, List.of(),
                "2026-10-05 14:59:32", ToolInvocation.AsOfKind.CALL, false, true, 0L);
    }

    static ToolInvocation failedTruth(String resultText) {
        return new ToolInvocation("get_quote", Map.of("code", "600519"), resultText, List.of(),
                null, ToolInvocation.AsOfKind.DATA, true, false, 0L);
    }

    static List<ToolInvocation> pool(ToolInvocation... invocations) {
        return List.of(invocations);
    }
}
