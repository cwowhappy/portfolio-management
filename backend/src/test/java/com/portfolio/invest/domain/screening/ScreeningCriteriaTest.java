package com.portfolio.invest.domain.screening;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScreeningCriteriaTest {

    @DisplayName("全空条件返回false")
    @Test
    void givenAllNullConditions_whenHasAnyCondition_thenReturnFalse() {
        var c = new ScreeningCriteria(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, "pe_ttm", SortDirection.ASC, 200);
        assertThat(c.hasAnyCondition()).isFalse();
    }

    @DisplayName("任一条件非空返回true")
    @Test
    void givenAnyNonNullCondition_whenHasAnyCondition_thenReturnTrue() {
        var c = new ScreeningCriteria(new BigDecimal("20"), null, null, null, null, null,
                null, null, null, null, null, null, null, null, "pe_ttm", SortDirection.ASC, 200);
        assertThat(c.hasAnyCondition()).isTrue();
    }

    @DisplayName("行业条件单独也算有条件")
    @Test
    void givenIndustryConditionOnly_whenHasAnyCondition_thenReturnTrue() {
        var c = new ScreeningCriteria(null, null, null, null, null, null, null, null,
                null, null, null, null, "801780", null, "pe_ttm", SortDirection.ASC, 200);
        assertThat(c.hasAnyCondition()).isTrue();
    }

    @DisplayName("指数条件单独也算有条件；白名单外拒绝")
    @Test
    void givenIndexCondition_whenHasAnyConditionAndWhitelist_thenBehave() {
        var c = new ScreeningCriteria(null, null, null, null, null, null, null, null,
                null, null, null, null, null, "000300", "pe_ttm", SortDirection.ASC, 200);
        assertThat(c.hasAnyCondition()).isTrue();

        assertThatThrownBy(() -> new ScreeningCriteria(null, null, null, null, null, null, null, null,
                null, null, null, null, null, "000016", "pe_ttm", SortDirection.ASC, 200))
                .isInstanceOfSatisfying(ScreeningException.class,
                        e -> assertThat(e.code()).isEqualTo(ScreeningErrorCode.INVALID_INDEX));
    }
}
