package com.portfolio.invest.domain.valuation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PercentileTest {

    @Test
    void 当前值或历史为null时返回null() {
        assertThat(Percentile.of(null, List.of(BigDecimal.ONE))).isNull();
        assertThat(Percentile.of(BigDecimal.ONE, null)).isNull();
    }

    @Test
    void 空历史返回null表示数据积累中() {
        assertThat(Percentile.of(BigDecimal.valueOf(18), List.of())).isNull();
    }

    @Test
    void 当前值低于全部历史返回0() {
        List<BigDecimal> history = List.of(BigDecimal.valueOf(20), BigDecimal.valueOf(25), BigDecimal.valueOf(30));
        assertThat(Percentile.of(BigDecimal.valueOf(10), history)).isEqualByComparingTo("0.00");
    }

    @Test
    void 当前值高于全部历史返回100() {
        List<BigDecimal> history = List.of(BigDecimal.valueOf(10), BigDecimal.valueOf(15));
        assertThat(Percentile.of(BigDecimal.valueOf(20), history)).isEqualByComparingTo("100.00");
    }

    @Test
    void 中间值按小于样本占比计算() {
        List<BigDecimal> history = List.of(BigDecimal.valueOf(10), BigDecimal.valueOf(20), BigDecimal.valueOf(30), BigDecimal.valueOf(40));
        // 当前 25：小于 25 的有 10、20 → 2/4 = 50%
        assertThat(Percentile.of(BigDecimal.valueOf(25), history)).isEqualByComparingTo("50.00");
    }
}
