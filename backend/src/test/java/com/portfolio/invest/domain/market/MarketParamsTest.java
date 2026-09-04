package com.portfolio.invest.domain.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarketParamsTest {

    @DisplayName("normalizeQuery去空白并抛INVALID_QUERY")
    @Test
    void givenNullOrBlankQuery_whenNormalizeQuery_thenTrimOrThrowInvalidQuery() {
        assertThat(MarketParams.normalizeQuery(" 茅台 ")).isEqualTo("茅台");
        assertThatThrownBy(() -> MarketParams.normalizeQuery(null))
                .isInstanceOf(MarketDataException.class).hasMessageContaining("搜索关键词不能为空");
        assertThatThrownBy(() -> MarketParams.normalizeQuery("   "))
                .isInstanceOf(MarketDataException.class).hasMessageContaining("搜索关键词不能为空");
    }

    @DisplayName("kltOf映射周期并抛INVALID_PERIOD")
    @Test
    void givenSupportedAndUnknownPeriods_whenKltOf_thenMapOrThrowInvalidPeriod() {
        assertThat(MarketParams.kltOf("day")).isEqualTo(101);
        assertThat(MarketParams.kltOf("week")).isEqualTo(102);
        assertThat(MarketParams.kltOf("month")).isEqualTo(103);
        assertThat(MarketParams.kltOf(null)).isEqualTo(101);
        assertThatThrownBy(() -> MarketParams.kltOf("foo"))
                .isInstanceOf(MarketDataException.class).hasMessageContaining("period 仅支持");
    }

    @DisplayName("clampLimit夹取与缺省")
    @Test
    void givenVaryingLimitInputs_whenClampLimit_thenClampAndDefault() {
        assertThat(MarketParams.clampLimit(60, 5, 120, 500)).isEqualTo(60);
        assertThat(MarketParams.clampLimit(0, 5, 120, 500)).isEqualTo(120);
        assertThat(MarketParams.clampLimit(9999, 5, 120, 500)).isEqualTo(500);
    }
}
