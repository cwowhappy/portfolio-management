package com.portfolio.invest.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 计算类容差维面 dataFidelityTolerance（MS-30 E1 Task 12，需求决策 #15）：抽取答案中的
 * 阿拉伯数字（千分位归一、负号不参与——模型常写「下跌1.17%」无负号，方向表述交 judge），
 * 对锚值逐一判「存在任一数字落在 ±pct% 内」（{@link CalcTolerance#within}，main 源集纯函数）。
 * <p>抽取边界（钉死）：锚驱动逐锚核对——每锚在答案抽取数字集中找任一 within 命中，全部锚
 * 命中才 PASS（"任一数字与锚 within 即该锚过"与"取与锚最近数字再判 within"逻辑等价：
 * within 命中者必为最近，取前者实现更直白）。中文数字/百分比措辞（"百分之五十二"）不抽取。
 * test 源集经 compileClasspath += eval.output 可见 eval 类（build.gradle 既有接线，
 * 沿 QuestionLoaderTest 同包先例落位）。
 */
class AssertionEngineToleranceTest {

    private static final String DIM = "dataFidelityTolerance";

    /** 仅声明容差维面的最小题（其余维面全空 → SKIP，不影响目标维面定位）。 */
    private static EvalQuestion question(EvalQuestion.DataFidelityTolerance tolerance) {
        return new EvalQuestion("mc-test", "METRIC_CALC", "stub", List.of("测试问题"),
                new EvalQuestion.Expect(null, null, null, null, null, null, null, null,
                        tolerance, null),
                "rubric-answer-quality", null);
    }

    private static AguiEventExtractor.Transcript transcript(String answer) {
        return new AguiEventExtractor.Transcript(List.of(), answer, 0,
                new AguiEventExtractor.TokenUsage(null, null, null, 0), List.of(), List.of());
    }

    private static AssertionEngine.DimensionResult evaluate(EvalQuestion q, String answer) {
        return AssertionEngine.evaluate(q, transcript(answer)).stream()
                .filter(d -> DIM.equals(d.name())).findFirst().orElseThrow();
    }

    @DisplayName("推导值命中：答案数字与锚 ±2% 内 → PASS（22.40 对锚 22.4）")
    @Test
    void givenAnswerWithComputedValue_whenEvaluated_thenPass() {
        AssertionEngine.DimensionResult r = evaluate(
                question(new EvalQuestion.DataFidelityTolerance(List.of(22.4), null)),
                "按最新价1735.86元与EPS 77.5元计算，市盈率约为22.40倍。");
        assertThat(r.status()).isEqualTo(AssertionEngine.Status.PASS);
        assertThat(r.expected()).contains("22.4").contains("±2%");
    }

    @DisplayName("推导错误：答案数字全部超界 → FAIL，报告带期望值与答案原文摘录（需求 §边界：人工复核）")
    @Test
    void givenWrongComputedValue_whenEvaluated_thenFailWithExpectAndExcerpt() {
        AssertionEngine.DimensionResult r = evaluate(
                question(new EvalQuestion.DataFidelityTolerance(List.of(22.4), null)),
                "按最新价与EPS计算，市盈率约为19.6倍。");
        assertThat(r.status()).isEqualTo(AssertionEngine.Status.FAIL);
        assertThat(r.actual()).contains("19.6");          // 抽取数字可读
        assertThat(r.detail()).contains("22.4");          // 期望值呈现
        assertThat(r.detail()).contains("19.6");          // 答案原文摘录呈现
    }

    @DisplayName("抽取形态：千分位 1,735.86 归一命中；负涨跌幅度口径（下跌1.17% 对锚 1.17）命中")
    @Test
    void givenThousandSeparatorAndMagnitudeForms_whenEvaluated_thenPass() {
        AssertionEngine.DimensionResult r = evaluate(
                question(new EvalQuestion.DataFidelityTolerance(List.of(1735.86, 1.17), null)),
                "收盘价1,735.86元，下跌1.17%。");
        assertThat(r.status()).isEqualTo(AssertionEngine.Status.PASS);
    }

    @DisplayName("逐锚核对：多锚缺一即 FAIL，未命中锚逐个列出")
    @Test
    void givenAnswerMissingOneAnchor_whenEvaluated_thenFailListingMissed() {
        AssertionEngine.DimensionResult r = evaluate(
                question(new EvalQuestion.DataFidelityTolerance(List.of(21802.0, 2.18), null)),
                "总市值约21802亿元。");
        assertThat(r.status()).isEqualTo(AssertionEngine.Status.FAIL);
        assertThat(r.actual()).contains("2.18");
    }

    @DisplayName("题面 tolerancePct 覆盖缺省：5% 下 4.2% 偏差 PASS，同偏差按缺省 2% FAIL")
    @Test
    void givenPerQuestionTolerance_whenEvaluated_thenOverridesDefault() {
        String answer = "按近一年区间线性位置估算，当前分位约为54.2%。"; // |54.2-52|/52 ≈ 4.2%
        AssertionEngine.DimensionResult overridden = evaluate(
                question(new EvalQuestion.DataFidelityTolerance(List.of(52.0), 5)), answer);
        assertThat(overridden.status()).isEqualTo(AssertionEngine.Status.PASS);
        assertThat(overridden.expected()).contains("±5%");

        AssertionEngine.DimensionResult defaulted = evaluate(
                question(new EvalQuestion.DataFidelityTolerance(List.of(52.0), null)), answer);
        assertThat(defaulted.status()).isEqualTo(AssertionEngine.Status.FAIL);
    }

    @DisplayName("配置下传通道：三参 evaluate 以入参为缺省容差（EvalRunner 读 invest.eval.calc-tolerance-pct 传入）")
    @Test
    void givenCustomDefaultPct_whenEvaluatedViaOverload_thenApplied() {
        EvalQuestion q = question(new EvalQuestion.DataFidelityTolerance(List.of(52.0), null));
        AguiEventExtractor.Transcript t = transcript("当前分位约为54.2%。"); // 4.2% 偏差
        AssertionEngine.DimensionResult withTen = AssertionEngine.evaluate(q, t, 10).stream()
                .filter(d -> DIM.equals(d.name())).findFirst().orElseThrow();
        assertThat(withTen.status()).isEqualTo(AssertionEngine.Status.PASS);
        assertThat(withTen.expected()).contains("±10%");
        AssertionEngine.DimensionResult withTwo = AssertionEngine.evaluate(q, t, 2).stream()
                .filter(d -> DIM.equals(d.name())).findFirst().orElseThrow();
        assertThat(withTwo.status()).isEqualTo(AssertionEngine.Status.FAIL);
    }

    @DisplayName("抽取边界：纯中文数字表述不抽取 → FAIL（题面与 rubric 引导阿拉伯数字，残余风险文档化）")
    @Test
    void givenChineseNumeralAnswer_whenEvaluated_thenFail() {
        AssertionEngine.DimensionResult r = evaluate(
                question(new EvalQuestion.DataFidelityTolerance(List.of(52.0), null)),
                "当前分位约为百分之五十二。");
        assertThat(r.status()).isEqualTo(AssertionEngine.Status.FAIL);
    }

    @DisplayName("缺省容差=2 与配置默认对齐：恰 2% 边界 PASS、略超 FAIL（DEFAULT_CALC_TOLERANCE_PCT）")
    @Test
    void givenDefaultOverload_whenEvaluated_thenDefaultPctIsTwo() {
        EvalQuestion q = question(new EvalQuestion.DataFidelityTolerance(List.of(100.0), null));
        assertThat(evaluate(q, "结果为98.0。").status()).isEqualTo(AssertionEngine.Status.PASS);
        assertThat(evaluate(q, "结果为97.9。").status()).isEqualTo(AssertionEngine.Status.FAIL);
        assertThat(AssertionEngine.DEFAULT_CALC_TOLERANCE_PCT).isEqualTo(2);
    }

    @DisplayName("未声明维面 → SKIPPED（expect 缺该槽位/锚值空集均不评估）")
    @Test
    void givenFacetUndeclared_whenEvaluated_thenSkipped() {
        assertThat(evaluate(question(null), "任意回答").status())
                .isEqualTo(AssertionEngine.Status.SKIPPED);
        assertThat(evaluate(question(new EvalQuestion.DataFidelityTolerance(List.of(), null)),
                "任意回答").status()).isEqualTo(AssertionEngine.Status.SKIPPED);
    }
}
