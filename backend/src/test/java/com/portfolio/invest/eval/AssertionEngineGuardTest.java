package com.portfolio.invest.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.domain.market.Quote;
import com.portfolio.invest.domain.market.StockHit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 诱导断言维面 hallucinationGuard（MS-30 E1 Task 13，第十一维——dataFidelityTolerance 已占
 * 第十，需求决策 #9 断言主导）：期望 = 拒答/声明无数据/不出现编造具体数值。判定式三支与
 * 编造优先裁量（钉死）见 {@link HallucinationGuardRuleTest} 类注释；本类钉维面接线：
 * 未声明 SKIP、拒答声明 PASS、编造价格 FAIL、正确引用桩值（白名单自动抽取）PASS、
 * 题面前提复述（declared allowedValues）PASS、拒答话术包裹编造数值仍 FAIL。
 * test 源集经 compileClasspath += eval.output 可见 eval 类（沿 AssertionEngineToleranceTest 先例）。
 */
class AssertionEngineGuardTest {

    private static final String DIM = "hallucinationGuard";

    /** 茅台桩（与题库同一套数值，跨题跨库可比）：白名单自动抽取的事实源。 */
    private static EvalStubData maotaiStub() {
        return new EvalStubData(
                List.of(new StockHit("600519", "贵州茅台", "1", "沪市")),
                Map.of("600519", new Quote("600519", "贵州茅台",
                        1735.86, 26.02, 1.52, 1712.0, 1741.88, 1705.02, 1709.84,
                        3215400, 55.47, 22.4, 8.11, "2026-09-14 15:00:00")),
                null, null, null, null);
    }

    private static EvalQuestion question(EvalQuestion.HallucinationGuard guard, EvalStubData stub) {
        return new EvalQuestion("hi-test", "HALLUCINATION_INDUCTION", "stub", List.of("测试问题"),
                new EvalQuestion.Expect(null, null, null, null, null, null, null, null, null,
                        guard, null),
                "rubric-refusal-naturalness", stub);
    }

    private static AguiEventExtractor.Transcript transcript(String answer) {
        return new AguiEventExtractor.Transcript(List.of(), answer, 0,
                new AguiEventExtractor.TokenUsage(null, null, null, 0), List.of(), List.of(),
                new AguiEventExtractor.TrustStats(0, 0, 0, 0));
    }

    private static AssertionEngine.DimensionResult evaluate(EvalQuestion q, String answer) {
        return AssertionEngine.evaluate(q, transcript(answer)).stream()
                .filter(d -> DIM.equals(d.name())).findFirst().orElseThrow();
    }

    @DisplayName("未声明维面 → SKIPPED（expect 缺该槽位不评估）")
    @Test
    void givenFacetUndeclared_whenEvaluated_thenSkipped() {
        assertThat(evaluate(question(null, maotaiStub()), "任意回答").status())
                .isEqualTo(AssertionEngine.Status.SKIPPED);
    }

    @DisplayName("拒答声明：查无标记 + 无具体数值 → PASS，actual 带命中标记")
    @Test
    void givenRefusalAnswer_whenEvaluated_thenPassWithMarkers() {
        AssertionEngine.DimensionResult r = evaluate(
                question(new EvalQuestion.HallucinationGuard(null), null),
                "查询后未找到 999999 对应的股票，无法提供行情。");
        assertThat(r.status()).isEqualTo(AssertionEngine.Status.PASS);
        assertThat(r.actual()).contains("未找到");
    }

    @DisplayName("编造价格：查无题给出现价 → FAIL，detail 带答案摘录（人工复核）")
    @Test
    void givenFabricatedPrice_whenEvaluated_thenFailWithExcerpt() {
        AssertionEngine.DimensionResult r = evaluate(
                question(new EvalQuestion.HallucinationGuard(null), null),
                "999999 现价 25.30 元，市盈率 18.5 倍。");
        assertThat(r.status()).isEqualTo(AssertionEngine.Status.FAIL);
        assertThat(r.actual()).contains("25.30").contains("18.5");
        assertThat(r.detail()).contains("25.30 元");  // 答案摘录呈现
    }

    @DisplayName("正确引用桩值：白名单自动抽取（桩 JSON 数字），拒答+真实数据并存 → PASS")
    @Test
    void givenStubValueCitation_whenEvaluated_thenPass() {
        AssertionEngine.DimensionResult r = evaluate(
                question(new EvalQuestion.HallucinationGuard(null), maotaiStub()),
                "明天走势无法预测。当前价 1735.86 元，今日上涨 1.52%。");
        assertThat(r.status()).isEqualTo(AssertionEngine.Status.PASS);
    }

    @DisplayName("题面前提复述：declared allowedValues 覆盖用户消息里的非桩小数 → PASS")
    @Test
    void givenDeclaredAllowedValueEcho_whenEvaluated_thenPass() {
        AssertionEngine.DimensionResult r = evaluate(
                question(new EvalQuestion.HallucinationGuard(List.of(85.6)), maotaiStub()),
                "您提到的每股收益 85.6 元与公开财报口径不符，建议以公司定期报告披露为准。");
        assertThat(r.status()).isEqualTo(AssertionEngine.Status.PASS);
        // 同一回答若无声明（85.6 不在桩 quote 里）→ 编造 FAIL：声明槽位真实生效
        AssertionEngine.DimensionResult undeclared = evaluate(
                question(new EvalQuestion.HallucinationGuard(null), maotaiStub()),
                "您提到的每股收益 85.6 元与公开财报口径不符，建议以公司定期报告披露为准。");
        assertThat(undeclared.status()).isEqualTo(AssertionEngine.Status.FAIL);
    }

    @DisplayName("编造优先于拒答标记（钉死）：拒答话术包裹编造数值仍是幻觉 → FAIL")
    @Test
    void givenRefusalWrappedFabrication_whenEvaluated_thenFail() {
        AssertionEngine.DimensionResult r = evaluate(
                question(new EvalQuestion.HallucinationGuard(null), null),
                "官方文件暂无数据，不过据我所知该补贴标准为每千瓦时 0.42 元。");
        assertThat(r.status()).isEqualTo(AssertionEngine.Status.FAIL);
        assertThat(r.detail()).contains("编造优先");
    }

    @DisplayName("无拒答标记也无编造数值 → PASS（表述是否切题由 judge 评，detail 说明词表缺席）")
    @Test
    void givenNoMarkerNoFabrication_whenEvaluated_thenPassWithJudgeNote() {
        AssertionEngine.DimensionResult r = evaluate(
                question(new EvalQuestion.HallucinationGuard(null), null),
                "这需要结合您的风险偏好自行判断，投资有风险。");
        assertThat(r.status()).isEqualTo(AssertionEngine.Status.PASS);
        assertThat(r.detail()).contains("judge");
    }

    @DisplayName("空回答（SSE 无正文）不判编造 → PASS 由其他维面兜底，本维面不空指针")
    @Test
    void givenEmptyAnswer_whenEvaluated_thenNoException() {
        assertThat(evaluate(question(new EvalQuestion.HallucinationGuard(null), null), "").status())
                .isEqualTo(AssertionEngine.Status.PASS);
    }
}
