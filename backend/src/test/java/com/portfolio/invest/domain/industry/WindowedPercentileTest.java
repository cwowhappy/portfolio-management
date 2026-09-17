package com.portfolio.invest.domain.industry;

import static org.assertj.core.api.Assertions.assertThat;

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
        // 249+1=250 个值中 1 个小于 → 1/250 = 0.40（除不尽验证 HALF_UP 到 2 位）
        assertThat(WindowedPercentile.of(current, series(250, 1, current)))
                .isEqualByComparingTo("0.40");
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
