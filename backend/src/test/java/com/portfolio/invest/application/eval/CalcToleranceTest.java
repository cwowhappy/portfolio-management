package com.portfolio.invest.application.eval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 计算类断言容差纯函数（MS-30 E1，需求决策 #15）：相对容差判定
 * {@code |actual-expected| ≤ |expected|×pct/100}（含边界——恰好 pct% 即过）。
 * main 源集纯函数，eval 源集 AssertionEngine 的 dataFidelityTolerance 维面复用；
 * pct 由调用方传入（CalcTolerance 不读配置——invest.eval.calc-tolerance-pct 由
 * EvalRunner 侧解析后下传）。实现须用 {@code diff ≤ expected×pct/100} 形态而非
 * {@code diff/expected×100 ≤ pct}——后者在边界值上撞浮点（0.02×100 = 2.000…4）假 FAIL。
 */
class CalcToleranceTest {

    @DisplayName("brief 四例：常规内/外 + 恰好 2% 边界过 / 略超 2% 不过")
    @Test
    void givenBriefCases_whenWithin_thenSemanticsHold() {
        assertThat(CalcTolerance.within(15.23, 15.20, 2)).isTrue();
        assertThat(CalcTolerance.within(15.23, 15.9, 2)).isFalse();
        assertThat(CalcTolerance.within(100, 98.0, 2)).isTrue(); // 恰好 2%，含边界
        assertThat(CalcTolerance.within(100, 97.9, 2)).isFalse();
    }

    @DisplayName("边界紧邻值：刚好压线 PASS、略超一线 FAIL（LLM 四舍五入表述的主要生存区）")
    @Test
    void givenAdjacentToBoundary_whenWithin_thenInclusiveEdge() {
        // 锚 22.4、2% 容差 → 允差 0.448：22.8 内、22.9 外
        assertThat(CalcTolerance.within(22.4, 22.8, 2)).isTrue();
        assertThat(CalcTolerance.within(22.4, 22.9, 2)).isFalse();
        // 负锚对称：幅度口径（|expected| 做分母/基准）
        assertThat(CalcTolerance.within(-100, -98.0, 2)).isTrue();
        assertThat(CalcTolerance.within(-100, -97.9, 2)).isFalse();
    }

    @DisplayName("零锚退化：相对容差无定义，退化为精确相等")
    @Test
    void givenZeroAnchor_whenWithin_thenExactEqualityOnly() {
        assertThat(CalcTolerance.within(0.0, 0.0, 2)).isTrue();
        assertThat(CalcTolerance.within(0.0, 0.001, 2)).isFalse();
    }

    @DisplayName("负 pct 防御：钳为 0（只允许完全相等），不抛异常")
    @Test
    void givenNegativePct_whenWithin_thenClampedToExact() {
        assertThat(CalcTolerance.within(10.0, 10.0, -5)).isTrue();
        assertThat(CalcTolerance.within(10.0, 10.1, -5)).isFalse();
    }

    @DisplayName("大数小容差：万亿级锚（21802 亿）2% 容差吃掉常规四舍五入")
    @Test
    void givenLargeAnchor_whenWithin_thenRoundingSurvives() {
        assertThat(CalcTolerance.within(21802, 21800, 2)).isTrue();
        assertThat(CalcTolerance.within(21802, 22190, 2)).isTrue(); // +1.78%，边界内
        assertThat(CalcTolerance.within(21802, 22300, 2)).isFalse(); // +2.29%，超界
    }
}
