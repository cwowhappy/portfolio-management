package com.portfolio.invest.domain.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AnnualReturnCalculatorTest {

    @DisplayName("已知答案：跨年三点 1000→1100（2025）→1320（2026）＝10%/20%")
    @Test
    void givenCrossYearSeries_whenYearlyTwr_thenTenAndTwenty() {
        List<DailyPoint> pts = List.of(
                new DailyPoint(LocalDate.of(2025, 12, 30), null, null, new BigDecimal("1000")),
                new DailyPoint(LocalDate.of(2025, 12, 31), null, null, new BigDecimal("1100")),
                new DailyPoint(LocalDate.of(2026, 1, 2), null, null, new BigDecimal("1320")));
        var byYear = AnnualReturnCalculator.yearlyTwr(new NavSeries(pts), List.of());
        assertThat(byYear).containsOnlyKeys(2025, 2026);
        assertThat(byYear.get(2025)).isCloseTo(new BigDecimal("0.10"), within(new BigDecimal("0.000001")));
        assertThat(byYear.get(2026)).isCloseTo(new BigDecimal("0.20"), within(new BigDecimal("0.000001")));
    }

    @DisplayName("年内中途转入不影响年度口径（外部流按日扣减）")
    @Test
    void givenMidYearDeposit_whenYearlyTwr_thenExcludesFlow() {
        List<DailyPoint> pts = List.of(
                new DailyPoint(LocalDate.of(2026, 1, 5), null, null, new BigDecimal("1000")),
                new DailyPoint(LocalDate.of(2026, 6, 1), null, null, new BigDecimal("1600")),
                new DailyPoint(LocalDate.of(2026, 12, 31), null, null, new BigDecimal("1700")));
        var byYear = AnnualReturnCalculator.yearlyTwr(new NavSeries(pts),
                List.of(new ExternalFlow(LocalDate.of(2026, 6, 1), new BigDecimal("500"))));
        assertThat(byYear.get(2026)).isCloseTo(new BigDecimal("0.16875"), within(new BigDecimal("0.000001")));
    }
}
