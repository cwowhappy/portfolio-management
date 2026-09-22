package com.portfolio.invest.domain.wiki;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrincipleRuleTest {

    private static final Instant NOW = Instant.parse("2026-09-22T00:00:00Z");

    @DisplayName("比例指标：0 < t ≤ 1")
    @Test
    void givenRatioMetric_whenCreate_thenBoundChecked() {
        PrincipleRule r = PrincipleRule.create(1L, PrincipleMetric.SINGLE_POSITION_RATIO,
                new BigDecimal("0.20"), true, "单票不超过20%", NOW);
        assertThat(r.threshold()).isEqualByComparingTo("0.20");
        assertThat(r.enabled()).isTrue();

        assertThatThrownBy(() -> PrincipleRule.create(1L, PrincipleMetric.SINGLE_POSITION_RATIO,
                BigDecimal.ZERO, true, null, NOW))
                .isInstanceOfSatisfying(WikiException.class,
                        e -> assertThat(e.code()).isEqualTo(WikiErrorCode.INVALID_INPUT));
        assertThatThrownBy(() -> PrincipleRule.create(1L, PrincipleMetric.SINGLE_POSITION_RATIO,
                new BigDecimal("1.01"), true, null, NOW))
                .isInstanceOfSatisfying(WikiException.class,
                        e -> assertThat(e.code()).isEqualTo(WikiErrorCode.INVALID_INPUT));
    }

    @DisplayName("倍数指标：t > 0")
    @Test
    void givenMultipleMetric_whenCreate_thenPositiveRequired() {
        PrincipleRule r = PrincipleRule.create(1L, PrincipleMetric.STOCK_PE_MAX,
                new BigDecimal("40"), true, "高估值不买", NOW);
        assertThat(r.metric().isRatio()).isFalse();

        assertThatThrownBy(() -> PrincipleRule.create(1L, PrincipleMetric.STOCK_PE_MAX,
                BigDecimal.ZERO, true, null, NOW))
                .isInstanceOfSatisfying(WikiException.class,
                        e -> assertThat(e.code()).isEqualTo(WikiErrorCode.INVALID_INPUT));
    }

    @DisplayName("说明超500字抛INVALID_INPUT；null 可")
    @Test
    void givenOverlongDescription_whenCreate_thenThrow() {
        assertThatThrownBy(() -> PrincipleRule.create(1L, PrincipleMetric.STOCK_PB_MAX,
                new BigDecimal("6"), true, "长".repeat(501), NOW))
                .isInstanceOfSatisfying(WikiException.class,
                        e -> assertThat(e.code()).isEqualTo(WikiErrorCode.INVALID_INPUT));
        PrincipleRule r = PrincipleRule.create(1L, PrincipleMetric.STOCK_PB_MAX,
                new BigDecimal("6"), false, null, NOW);
        assertThat(r.description()).isNull();
    }

    @DisplayName("update 返回新实例，metric 不可变")
    @Test
    void givenRule_whenUpdate_thenNewInstanceAndMetricKept() {
        PrincipleRule original = PrincipleRule.reconstitute(9L, 1L, PrincipleMetric.STOCK_PE_MAX,
                new BigDecimal("40"), true, null, NOW, NOW, 0L);
        PrincipleRule updated = original.update(new BigDecimal("30"), false, "收紧");
        assertThat(updated).isNotSameAs(original);
        assertThat(updated.metric()).isEqualTo(PrincipleMetric.STOCK_PE_MAX);
        assertThat(updated.threshold()).isEqualByComparingTo("30");
        assertThat(updated.enabled()).isFalse();
    }

    @DisplayName("metric 单位语义与中文标签")
    @Test
    void givenMetrics_whenInspect_thenRatioAndLabelCorrect() {
        assertThat(PrincipleMetric.SINGLE_POSITION_RATIO.isRatio()).isTrue();
        assertThat(PrincipleMetric.INDUSTRY_POSITION_RATIO.isRatio()).isTrue();
        assertThat(PrincipleMetric.STOCK_PE_MAX.isRatio()).isFalse();
        assertThat(PrincipleMetric.STOCK_PB_MAX.isRatio()).isFalse();
        assertThat(PrincipleMetric.SINGLE_POSITION_RATIO.label()).isEqualTo("单票仓位上限");
    }
}
