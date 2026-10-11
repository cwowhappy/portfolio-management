package com.portfolio.invest.agent.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.agent.trust.AnchorBatch;
import com.portfolio.invest.agent.trust.AnchorRecord;
import com.portfolio.invest.agent.trust.ConsistencyValidator;
import com.portfolio.invest.agent.trust.NumberExtractor;
import com.portfolio.invest.agent.trust.ToolInvocation;
import com.portfolio.invest.agent.trust.TrustVerdict;
import com.portfolio.invest.config.InvestProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 研报校验门单测（MS-32 B7，设计规格 §3.5/§3.7）：fail-closed 判定链五失败因 +
 * 假设锚点自动绑定 + 「关键假设」章节切分。确定性 fixture（真值池手构 ToolInvocation，
 * 不依赖真 LLM 断言门语义——需求验收条款）；容差边界断言走整数交叉相乘口径（|Δ|·den ≤
 * rel·num 的精确十进制形态，勿浮点直比）。
 *
 * <p>fixture 口径：判定链用例 contentMd 为正文文本、assumptionStatements 按签名独立传入
 * （生产由 B9 从 payload contentMd 经 {@code extractAssumptionStatements} 切分传入——编号行
 * 剥前缀后不含列表序号；正文内嵌编号行会使列表序号以裸数字 token 混入锚定统计，见任务报告
 * concern），章节切分语义由专用用例单独钉。
 */
class ReportValidationGateTest {

    /** 10 条真值（价格/市值/估值/百分比混合；边界拦用例摘除股息率一条→9 条）。 */
    private static final String TEN_TRUTHS =
            "现价1520.33元 目标价1620.50元 支撑位1450.00元 压力位1688.80元 总市值19000亿 "
                    + "市盈率25.30 市净率9.80 股息率3.10% 日均成交45.20亿 振幅2.80%";
    private static final String TEN_TRUTHS_WITHOUT_DIVIDEND = TEN_TRUTHS.replace("股息率3.10% ", "");

    /** 11 个数据性数字：10 与真值精确对应 + 1 个无真值百分比（82%）。 */
    private static final String ELEVEN_NUMBERS_MD =
            "现价1520.33元，目标价1620.50元，支撑位1450.00元，压力位1688.80元，"
                    + "总市值19000亿，市盈率25.30，市净率9.80，股息率3.10%，日均成交45.20亿，振幅2.80%，机构覆盖率82%。";

    private final ReportValidationGate gate = ReportValidationGate.forReport(new InvestProperties());

    // ———— 判定链：全过 ————

    @DisplayName("正常过门：verified 全过 + 假设逐条绑定 → passed=true、failReason=null、原文直通")
    @Test
    void givenVerifiedReportWithBoundAssumptions_whenEvaluate_thenPassed() {
        String contentMd = "现价986.50元，总市值8500亿，市盈率35.40。";

        ReportValidationGate.GateResult result = gate.evaluate(contentMd,
                List.of("现价986.50元具备估值支撑", "总市值8500亿的龙头地位稳固"),
                pool(truth("get_quote", "现价986.50元，总市值8500亿，市盈率35.40")));

        assertThat(result.passed()).isTrue();
        assertThat(result.failReason()).isNull();
        assertThat(result.feedbackLines()).isEmpty();
        assertThat(result.anchors().stats()).isEqualTo(new AnchorBatch.Stats(3, 0, 0));
        assertThat(result.correctedMd()).isEqualTo(contentMd);
        assertThat(result.bindings()).hasSize(2);
        assertThat(result.bindings().get(0).seq()).isEqualTo(1);
        assertThat(result.bindings().get(0).anchorIndex()).isEqualTo(0);
        assertThat(result.bindings().get(1).seq()).isEqualTo(2);
        assertThat(result.bindings().get(1).anchorIndex()).isEqualTo(1);
    }

    // ———— 判定链：五失败因 ————

    @DisplayName("空池：无任何成功工具调用 → fail-closed EMPTY_POOL（判定链首位）")
    @Test
    void givenEmptyPool_whenEvaluate_thenEmptyPoolFail() {
        ReportValidationGate.GateResult result = gate.evaluate("现价1520.33元。",
                List.of("现价1520.33元具备估值支撑"), List.of());

        assertThat(result.passed()).isFalse();
        assertThat(result.failReason()).isEqualTo(ReportValidationGate.FailReason.EMPTY_POOL);
        assertThat(result.feedbackLines()).singleElement().asString().contains("真值池为空");
    }

    @DisplayName("可归因错值：文本 15.20元 vs 真值 1520.33 → 自动修正 failure=0 仍过门，correctedMd 含 ⚠ 注记行")
    @Test
    void givenAttributableMisstatement_whenEvaluate_thenAutoCorrectedAndPassedWithNote() {
        ReportValidationGate.GateResult result = gate.evaluate("当前股价15.20元。",
                List.of("当前股价1520.33元处于上行通道"),
                pool(truth("get_quote", "{\"price\":1520.33}")));

        assertThat(result.passed()).isTrue();
        assertThat(result.failReason()).isNull();
        assertThat(result.correctedMd())
                .isEqualTo("当前股价1520.33元。\n> ⚠ 校验修正：原文误述 15.20元");
        assertThat(result.bindings()).hasSize(1);
        assertThat(result.bindings().get(0).anchorIndex()).isZero();
    }

    @DisplayName("修正失败：替换后融合仍偏差且重试耗尽（maxRetries=0 沿对话档注入）→ CORRECTION_FAILURES 拦")
    @Test
    void givenDegradedCorrection_whenEvaluate_thenCorrectionFailuresBlocked() {
        InvestProperties props = new InvestProperties();
        props.getTrust().setCorrection(new InvestProperties.Trust.CorrectionSettings(0));
        ReportValidationGate zeroRetryGate = ReportValidationGate.forReport(props);

        ReportValidationGate.GateResult result = zeroRetryGate.evaluate("波动区间15-152元。",
                List.of("支撑位15元有效"),
                pool(truth("get_quote", "15元"), truth("get_quote", "152元")));

        assertThat(result.passed()).isFalse();
        assertThat(result.failReason()).isEqualTo(ReportValidationGate.FailReason.CORRECTION_FAILURES);
        assertThat(result.feedbackLines()).anyMatch(line -> line.contains("-152元"));
        assertThat(result.feedbackLines()).anyMatch(line -> line.contains("152元"));
    }

    @DisplayName("未溯源 10% 边界（过）：11 数字 1 未锚 = 9.09% ≤ 10% → 过门")
    @Test
    void givenOneUnverifiedOfEleven_whenEvaluate_thenPasses() {
        ReportValidationGate.GateResult result = gate.evaluate(ELEVEN_NUMBERS_MD,
                List.of("现价1520.33元具备估值支撑"),
                pool(truth("get_quote", TEN_TRUTHS)));

        assertThat(result.anchors().stats()).isEqualTo(new AnchorBatch.Stats(10, 0, 1));
        assertThat(result.passed()).isTrue();
        assertThat(result.failReason()).isNull();
    }

    @DisplayName("未溯源 10% 边界（拦）：11 数字 2 未锚 = 18.18% > 10% → UNVERIFIED_RATIO，反馈含未锚定数字清单")
    @Test
    void givenTwoUnverifiedOfEleven_whenEvaluate_thenUnverifiedRatioBlocked() {
        ReportValidationGate.GateResult result = gate.evaluate(ELEVEN_NUMBERS_MD,
                List.of("现价1520.33元具备估值支撑"),
                pool(truth("get_quote", TEN_TRUTHS_WITHOUT_DIVIDEND)));

        assertThat(result.passed()).isFalse();
        assertThat(result.failReason()).isEqualTo(ReportValidationGate.FailReason.UNVERIFIED_RATIO);
        assertThat(result.feedbackLines()).anyMatch(line -> line.contains("2/11"));
        assertThat(result.feedbackLines()).anyMatch(line -> line.contains("82%"));
        assertThat(result.feedbackLines()).anyMatch(line -> line.contains("3.10%"));
    }

    @DisplayName("假设缺失：无「## 关键假设」章节 → 切分为空 + ASSUMPTIONS_MISSING")
    @Test
    void givenNoAssumptionSection_whenEvaluate_thenAssumptionsMissing() {
        String contentMd = "现价1520.33元。";

        List<String> statements = ReportValidationGate.extractAssumptionStatements(contentMd);
        ReportValidationGate.GateResult result = gate.evaluate(contentMd, statements,
                pool(truth("get_quote", "1520.33元")));

        assertThat(statements).isEmpty();
        assertThat(result.passed()).isFalse();
        assertThat(result.failReason()).isEqualTo(ReportValidationGate.FailReason.ASSUMPTIONS_MISSING);
        assertThat(result.feedbackLines()).singleElement().asString().contains("关键假设");
    }

    @DisplayName("假设未绑定：条目无任何数字（零锚点）→ ASSUMPTION_UNBOUND，反馈列出未绑定条目")
    @Test
    void givenUnbindableAssumption_whenEvaluate_thenAssumptionUnbound() {
        ReportValidationGate.GateResult result = gate.evaluate("现价1520.33元。",
                List.of("白酒行业进入新一轮上行周期"),
                pool(truth("get_quote", "1520.33元")));

        assertThat(result.passed()).isFalse();
        assertThat(result.failReason()).isEqualTo(ReportValidationGate.FailReason.ASSUMPTION_UNBOUND);
        assertThat(result.bindings()).hasSize(1);
        assertThat(result.bindings().get(0).seq()).isEqualTo(1);
        assertThat(result.bindings().get(0).anchorIndex()).isEqualTo(-1);
        assertThat(result.feedbackLines()).anyMatch(line -> line.contains("白酒行业进入新一轮上行周期"));
    }

    // ———— 容差边界（整数交叉相乘断言纪律） ————

    @DisplayName("绑定容差边界：|Δ|·100 与 1·1000 恰等（|1010−1000| = 0.01×1000）→ 绑；越线 10.1 → -1")
    @Test
    void givenToleranceBoundary_whenBind_thenExactBindsAndBeyondUnbound() {
        List<AnchorRecord> anchors = List.of(anchor("1000元", TrustVerdict.VERIFIED));
        BigDecimal relative = new BigDecimal("0.01");

        // 交叉相乘：|1010−1000|×100 = 1000 ≤ 1×1000 → 恰在容差线上（≤ 语义，精确十进制）
        List<AssumptionBinder.Binding> atBoundary =
                AssumptionBinder.bind(List.of("目标价1010元"), anchors, relative);
        // 交叉相乘：|1010.1−1000|×100 = 1010 > 1×1000 → 越线
        List<AssumptionBinder.Binding> beyond =
                AssumptionBinder.bind(List.of("目标价1010.1元"), anchors, relative);

        assertThat(atBoundary.get(0).anchorIndex()).isZero();
        assertThat(beyond.get(0).anchorIndex()).isEqualTo(-1);
    }

    @DisplayName("绑定资格：unverified 锚不参与（sourced 参与）、百分比维度不一致不绑、语句无数字 → -1")
    @Test
    void givenAnchorEligibilityRules_whenBind_thenOnlyVerifiedSourcedSameDimensionBound() {
        BigDecimal relative = new BigDecimal("0.01");

        assertThat(AssumptionBinder.bind(List.of("目标价1000元"),
                List.of(anchor("1000元", TrustVerdict.UNVERIFIED)), relative)
                .get(0).anchorIndex()).isEqualTo(-1);
        assertThat(AssumptionBinder.bind(List.of("目标价1000元"),
                List.of(anchor("1000元", TrustVerdict.SOURCED)), relative)
                .get(0).anchorIndex()).isZero();
        assertThat(AssumptionBinder.bind(List.of("股息率10%"),
                List.of(anchor("10元", TrustVerdict.VERIFIED)), relative)
                .get(0).anchorIndex()).isEqualTo(-1);
        assertThat(AssumptionBinder.bind(List.of("行业进入上行周期"),
                List.of(anchor("1000元", TrustVerdict.VERIFIED)), relative)
                .get(0).anchorIndex()).isEqualTo(-1);
    }

    @DisplayName("严格档容差边界（门级）：|9.09−9.00| 恰 = 0.01×9 → verified 直通、无 ⚠ 注记")
    @Test
    void givenExactBoundaryThroughGate_whenEvaluate_thenPassUncorrected() {
        String contentMd = "现价9.09元。";

        ReportValidationGate.GateResult result = gate.evaluate(contentMd,
                List.of("现价9.09元为合理买点"),
                pool(truth("get_quote", "9.00元")));

        assertThat(result.passed()).isTrue();
        assertThat(result.correctedMd()).isEqualTo(contentMd);
        assertThat(result.correctedMd()).doesNotContain("⚠");
    }

    @DisplayName("严格档独立容差：10.2元 vs 10元（偏差 2%）对话档 verified、report 档（1%）拦截自动修正")
    @Test
    void givenDeviationWithinConversationTolerance_whenEvaluate_thenStrictGateCorrects() {
        List<ToolInvocation> pool = pool(truth("get_quote", "10元"));
        ConsistencyValidator conversation = new ConsistencyValidator(new InvestProperties().getTrust());
        assertThat(conversation.verify(NumberExtractor.extract("现价10.2元"), pool)
                .anchors().getFirst().state()).isEqualTo(TrustVerdict.VERIFIED);

        ReportValidationGate.GateResult result = gate.evaluate("现价10.2元。",
                List.of("现价10元为合理买点"), pool);

        assertThat(result.passed()).isTrue();
        assertThat(result.correctedMd())
                .isEqualTo("现价10元。\n> ⚠ 校验修正：原文误述 10.2元");
    }

    // ———— 「关键假设」章节切分 ————

    @DisplayName("章节切分：标题节内 ^\\d+\\. 编号行（剥编号前缀）、下一 ## 截止；无标题 → 空列表")
    @Test
    void givenReportMarkdown_whenExtractAssumptionStatements_thenNumberedLinesInSectionOnly() {
        String contentMd = "# 研报\n\n## 关键假设\n1. 假设甲：现价1520.33元\n2. 假设乙：市值19000亿\n\n## 风险提示\n1. 风险一";

        assertThat(ReportValidationGate.extractAssumptionStatements(contentMd))
                .containsExactly("假设甲：现价1520.33元", "假设乙：市值19000亿");
        assertThat(ReportValidationGate.extractAssumptionStatements("全文无假设章节")).isEmpty();
        assertThat(ReportValidationGate.extractAssumptionStatements(null)).isEmpty();
    }

    // ———— 脚手架 ————

    private static ToolInvocation truth(String tool, String resultText) {
        return new ToolInvocation(tool, Map.of("code", "600519"), resultText, List.of(),
                "2026-10-11 09:00:00", ToolInvocation.AsOfKind.DATA, false, false, 120L);
    }

    private static List<ToolInvocation> pool(ToolInvocation... invocations) {
        return List.of(invocations);
    }

    private static AnchorRecord anchor(String snippet, TrustVerdict state) {
        return new AnchorRecord(snippet, 1, state, "get_quote", Map.of("code", "600519"),
                "2026-10-11 09:00:00", ToolInvocation.AsOfKind.DATA, snippet);
    }
}
