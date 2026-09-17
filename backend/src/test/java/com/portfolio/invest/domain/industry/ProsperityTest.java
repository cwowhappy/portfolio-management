package com.portfolio.invest.domain.industry;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ProsperityTest {

    @Test
    void up_requires_both_roeDelta_and_revenue() {
        assertThat(Prosperity.of(new BigDecimal("1"), new BigDecimal("10"))).isEqualTo(Prosperity.UP);
        assertThat(Prosperity.of(new BigDecimal("0.99"), new BigDecimal("10"))).isEqualTo(Prosperity.FLAT);
        assertThat(Prosperity.of(new BigDecimal("1"), new BigDecimal("9.99"))).isEqualTo(Prosperity.FLAT);
    }

    @Test
    void down_triggers_on_either_side() {
        assertThat(Prosperity.of(new BigDecimal("-1"), new BigDecimal("50"))).isEqualTo(Prosperity.DOWN);
        assertThat(Prosperity.of(new BigDecimal("10"), new BigDecimal("-10"))).isEqualTo(Prosperity.DOWN);
        assertThat(Prosperity.of(new BigDecimal("-0.99"), new BigDecimal("-9.99"))).isEqualTo(Prosperity.FLAT);
    }

    @Test
    void null_input_returns_null() {
        assertThat(Prosperity.of(null, new BigDecimal("10"))).isNull();
        assertThat(Prosperity.of(new BigDecimal("1"), null)).isNull();
    }
}
