package com.portfolio.invest.domain.industry;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProsperityTest {

    @DisplayName("ROE趋势与营收增速同时达门槛标注上行")
    @Test
    void givenBothIndicatorsAtThreshold_whenClassify_thenReturnUp() {
        assertThat(Prosperity.of(new BigDecimal("1"), new BigDecimal("10"))).isEqualTo(Prosperity.UP);
        assertThat(Prosperity.of(new BigDecimal("0.99"), new BigDecimal("10"))).isEqualTo(Prosperity.FLAT);
        assertThat(Prosperity.of(new BigDecimal("1"), new BigDecimal("9.99"))).isEqualTo(Prosperity.FLAT);
    }

    @DisplayName("ROE趋势或营收增速任一跌破下限标注下行")
    @Test
    void givenEitherDownsideIndicator_whenClassify_thenReturnDown() {
        assertThat(Prosperity.of(new BigDecimal("-1"), new BigDecimal("50"))).isEqualTo(Prosperity.DOWN);
        assertThat(Prosperity.of(new BigDecimal("10"), new BigDecimal("-10"))).isEqualTo(Prosperity.DOWN);
        assertThat(Prosperity.of(new BigDecimal("-0.99"), new BigDecimal("-9.99"))).isEqualTo(Prosperity.FLAT);
    }

    @DisplayName("任一输入缺失返回null不标注")
    @Test
    void givenNullInput_whenClassify_thenReturnNull() {
        assertThat(Prosperity.of(null, new BigDecimal("10"))).isNull();
        assertThat(Prosperity.of(new BigDecimal("1"), null)).isNull();
    }
}
