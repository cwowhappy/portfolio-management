package com.portfolio.invest.domain.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RiskMetricsCalculatorTest {

    private static List<DatedIndex> idx(double... values) {
        LocalDate d = LocalDate.of(2026, 1, 5);
        var out = new java.util.ArrayList<DatedIndex>();
        for (double v : values) {
            out.add(new DatedIndex(d, BigDecimal.valueOf(v)));
            d = d.plusDays(1);
        }
        return out;
    }

    @DisplayName("已知答案：1→1.2→0.9→1.3 → MDD=25%（谷 0.9/峰 1.2），已恢复（1.3≥1.2）")
    @Test
    void givenVShape_whenMaxDrawdown_thenTwentyFivePercentRecovered() {
        var r = RiskMetricsCalculator.maxDrawdown(idx(1.0, 1.2, 0.9, 1.3));
        assertThat(r.mdd()).isCloseTo(new BigDecimal("0.25"), within(new BigDecimal("0.000001")));
        assertThat(r.peakDate()).isEqualTo(LocalDate.of(2026, 1, 6));
        assertThat(r.troughDate()).isEqualTo(LocalDate.of(2026, 1, 7));
        assertThat(r.recoveryDate()).isEqualTo(LocalDate.of(2026, 1, 8));
        assertThat(r.currentDrawdown()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @DisplayName("已知答案：未恢复——1→1.5→1.2 → MDD=20%，恢复日 null，当前回撤 20%")
    @Test
    void givenNotRecovered_whenMaxDrawdown_thenRecoveryNull() {
        var r = RiskMetricsCalculator.maxDrawdown(idx(1.0, 1.5, 1.2));
        assertThat(r.recoveryDate()).isNull();
        assertThat(r.currentDrawdown()).isCloseTo(new BigDecimal("0.2"), within(new BigDecimal("0.000001")));
    }

    @DisplayName("单边上行 → MDD=0（Calmar 分母为 0 的来源）；双谷取更深者")
    @Test
    void givenMonotonicUp_whenMaxDrawdown_thenZero() {
        var r = RiskMetricsCalculator.maxDrawdown(idx(1.0, 1.1, 1.2));
        assertThat(r.mdd()).isEqualByComparingTo(BigDecimal.ZERO);
        var deep = RiskMetricsCalculator.maxDrawdown(idx(1.0, 0.95, 0.99, 0.8, 0.97));
        assertThat(deep.mdd()).isCloseTo(new BigDecimal("0.2"), within(new BigDecimal("0.000001")));
        assertThat(deep.troughDate()).isEqualTo(LocalDate.of(2026, 1, 8));
    }

    @DisplayName("twrIndex：与 TwrCalculator.cumulative 同口径——D2 日初转入 500 → 指数 1×1.1×1.0625")
    @Test
    void givenMorningDeposit_whenTwrIndex_thenMatchesCumulative() {
        var pts = new java.util.ArrayList<DailyPoint>();
        pts.add(new DailyPoint(LocalDate.of(2026, 1, 5), BigDecimal.ZERO, BigDecimal.valueOf(1000), BigDecimal.valueOf(1000)));
        pts.add(new DailyPoint(LocalDate.of(2026, 1, 6), BigDecimal.ZERO, BigDecimal.valueOf(1600), BigDecimal.valueOf(1600)));
        pts.add(new DailyPoint(LocalDate.of(2026, 1, 7), BigDecimal.ZERO, BigDecimal.valueOf(1700), BigDecimal.valueOf(1700)));
        var idx = RiskMetricsCalculator.twrIndex(new NavSeries(pts),
                List.of(new ExternalFlow(LocalDate.of(2026, 1, 6), new BigDecimal("500"))));
        assertThat(idx.get(idx.size() - 1).index())
                .isCloseTo(new BigDecimal("1.16875"), within(new BigDecimal("0.000001")));
    }
}
