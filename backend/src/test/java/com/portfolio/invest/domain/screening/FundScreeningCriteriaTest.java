package com.portfolio.invest.domain.screening;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FundScreeningCriteriaTest {

    @DisplayName("全空条件返回false")
    @Test
    void givenAllNullConditions_whenHasAnyCondition_thenReturnFalse() {
        var c = new FundScreeningCriteria(null, null, null, null, "tracking_error_1y", SortDirection.ASC, 200);
        assertThat(c.hasAnyCondition()).isFalse();
    }

    @DisplayName("费率上限单独也算有条件")
    @Test
    void givenFeeRateMaxOnly_whenHasAnyCondition_thenReturnTrue() {
        var c = new FundScreeningCriteria(new BigDecimal("0.6"), null, null, null,
                "tracking_error_1y", SortDirection.ASC, 200);
        assertThat(c.hasAnyCondition()).isTrue();
    }

    @DisplayName("规模下限单独也算有条件")
    @Test
    void givenScaleMinOnly_whenHasAnyCondition_thenReturnTrue() {
        var c = new FundScreeningCriteria(null, new BigDecimal("100"), null, null,
                "tracking_error_1y", SortDirection.ASC, 200);
        assertThat(c.hasAnyCondition()).isTrue();
    }

    @DisplayName("跟踪误差上限单独也算有条件")
    @Test
    void givenTrackingErrorMaxOnly_whenHasAnyCondition_thenReturnTrue() {
        var c = new FundScreeningCriteria(null, null, new BigDecimal("0.05"), null,
                "tracking_error_1y", SortDirection.ASC, 200);
        assertThat(c.hasAnyCondition()).isTrue();
    }

    @DisplayName("类别条件单独也算有条件；白名单外拒绝")
    @Test
    void givenCategory_whenHasAnyConditionAndWhitelist_thenBehave() {
        var c = new FundScreeningCriteria(null, null, null, "宽基", "tracking_error_1y", SortDirection.ASC, 200);
        assertThat(c.hasAnyCondition()).isTrue();

        assertThatThrownBy(() -> new FundScreeningCriteria(null, null, null, "货币",
                "tracking_error_1y", SortDirection.ASC, 200))
                .isInstanceOfSatisfying(ScreeningException.class,
                        e -> assertThat(e.code()).isEqualTo(ScreeningErrorCode.INVALID_CATEGORY));
    }
}
