package com.portfolio.invest.domain.screening;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ScreeningCriteriaTest {

    @DisplayName("全空条件返回false")
    @Test
    void allNullConditionsReturnFalse() {
        var c = new ScreeningCriteria(null, null, null, null, null, null, null, null,
                null, null, null, null, null, "pe_ttm", SortDirection.ASC, 200);
        assertThat(c.hasAnyCondition()).isFalse();
    }

    @DisplayName("任一条件非空返回true")
    @Test
    void anyNonNullConditionReturnsTrue() {
        var c = new ScreeningCriteria(new BigDecimal("20"), null, null, null, null, null,
                null, null, null, null, null, null, null, "pe_ttm", SortDirection.ASC, 200);
        assertThat(c.hasAnyCondition()).isTrue();
    }

    @DisplayName("行业条件单独也算有条件")
    @Test
    void industryConditionAloneCountsAsCondition() {
        var c = new ScreeningCriteria(null, null, null, null, null, null, null, null,
                null, null, null, null, "801780", "pe_ttm", SortDirection.ASC, 200);
        assertThat(c.hasAnyCondition()).isTrue();
    }
}
