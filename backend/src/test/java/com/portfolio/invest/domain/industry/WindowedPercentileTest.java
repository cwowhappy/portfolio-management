package com.portfolio.invest.domain.industry;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.domain.valuation.Percentile;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WindowedPercentileTest {

    private static List<BigDecimal> series(int size, int belowCount, BigDecimal current) {
        // belowCount 个严格小于 current + 1 个等于 + 其余大于（等于不计入 below，对齐经验分布口径）
        List<BigDecimal> s = new ArrayList<>();
        for (int i = 0; i < belowCount; i++) s.add(current.subtract(BigDecimal.ONE));
        s.add(current);
        for (int i = belowCount + 1; i < size; i++) s.add(current.add(BigDecimal.ONE));
        return s;
    }

    @DisplayName("250个样本按严格小于占比HALF_UP保留两位")
    @Test
    void givenSeriesOf250_whenPercentile_thenReturnBelowRatio() {
        var current = new BigDecimal("50");
        // 250 个值中 100 个严格小于 → 100/250 = 40.00
        assertThat(WindowedPercentile.of(current, series(250, 100, current)))
                .isEqualByComparingTo("40.00");
        // 250 个值中 1 个严格小于 → 1/250：250 因子只含 2/5、商可整除，0.40 非舍入所得（除不尽的 HALF_UP 真进位见下方用例）
        assertThat(WindowedPercentile.of(current, series(250, 1, current)))
                .isEqualByComparingTo("0.40");
    }

    @DisplayName("除不尽商真实触发 HALF_UP 进位到 2 位")
    @Test
    void givenNonTerminatingRatio_whenPercentile_thenHalfUpRoundsToTwoDigits() {
        var current = new BigDecimal("50");
        // 251 个值中 1 个严格小于：100×1/251 = 0.39840…（251 为素数，除不尽）→ HALF_UP 进位 0.39→0.40（DOWN 将得 0.39）
        assertThat(WindowedPercentile.of(current, series(251, 1, current)))
                .isEqualByComparingTo("0.40");
        // 300 个值中 137 个严格小于：100×137/300 = 45.6666…（300 含因子 3，除不尽）→ HALF_UP 进位 45.66→45.67
        assertThat(WindowedPercentile.of(current, series(300, 137, current)))
                .isEqualByComparingTo("45.67");
    }

    @DisplayName("同输入与估值域 Percentile 输出逐值相等（分位语义锚定）")
    @Test
    void givenSameNonNullSeries_whenWindowedAndValuationPercentileCompared_thenOutputsEqual() {
        // 测试源集无跨域 import 守护（主代码域间零依赖口径不变）；Percentile.of 不剔 null（会 NPE），锚定用无 null 序列
        var current = new BigDecimal("50");
        assertThat(WindowedPercentile.of(current, series(250, 100, current)))
                .isEqualByComparingTo(Percentile.of(current, series(250, 100, current)));
        assertThat(WindowedPercentile.of(current, series(251, 1, current)))
                .isEqualByComparingTo(Percentile.of(current, series(251, 1, current)));
        assertThat(WindowedPercentile.of(current, series(300, 137, current)))
                .isEqualByComparingTo(Percentile.of(current, series(300, 137, current)));
    }

    @DisplayName("样本不足250或输入缺失返回null")
    @Test
    void givenSeriesShorterThan250_whenPercentile_thenReturnNull() {
        assertThat(WindowedPercentile.of(new BigDecimal("50"), series(249, 100, new BigDecimal("50")))).isNull();
        assertThat(WindowedPercentile.of(new BigDecimal("50"), List.of())).isNull();
        assertThat(WindowedPercentile.of(null, series(250, 1, new BigDecimal("50")))).isNull();
    }

    @DisplayName("列表含null元素时分母按非空样本计")
    @Test
    void givenSeriesWithNullElements_whenPercentile_thenExcludedFromDenominator() {
        var values = new ArrayList<BigDecimal>();
        for (int i = 0; i < 250; i++) values.add(new BigDecimal("10"));
        values.add(null); // 251 个元素、250 个非空——仍够门槛，分母按非空计
        assertThat(WindowedPercentile.of(new BigDecimal("20"), values)).isEqualByComparingTo("100.00");
    }
}
