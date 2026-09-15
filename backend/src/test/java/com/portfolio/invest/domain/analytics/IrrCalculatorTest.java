package com.portfolio.invest.domain.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class IrrCalculatorTest {

    @DisplayName("已知答案：−1000 一年后回收 1100 → 10%")
    @Test
    void givenSingleYearGain_whenXirr_thenTenPercent() {
        var r = IrrCalculator.xirr(List.of(
                new DatedAmount(LocalDate.of(2025, 1, 1), new BigDecimal("-1000")),
                new DatedAmount(LocalDate.of(2026, 1, 1), new BigDecimal("1100"))));
        assertThat(r).isPresent();
        assertThat(r.get()).isCloseTo(new BigDecimal("0.10"), within(new BigDecimal("0.000001")));
    }

    @DisplayName("已知答案：两笔回收 100/1100 精确解 r=0.10（11x²+x−10=0 → x=10/11）")
    @Test
    void givenTwoRecovers_whenXirr_thenTenPercentExactly() {
        var r = IrrCalculator.xirr(List.of(
                new DatedAmount(LocalDate.of(2025, 1, 1), new BigDecimal("-1000")),
                new DatedAmount(LocalDate.of(2026, 1, 1), new BigDecimal("100")),
                new DatedAmount(LocalDate.of(2027, 1, 1), new BigDecimal("1100"))));
        assertThat(r).isPresent();
        assertThat(r.get()).isCloseTo(new BigDecimal("0.10"), within(new BigDecimal("0.000001")));
    }

    @DisplayName("已知答案：亏损 −1000→900 → −10%")
    @Test
    void givenLoss_whenXirr_thenMinusTenPercent() {
        var r = IrrCalculator.xirr(List.of(
                new DatedAmount(LocalDate.of(2025, 1, 1), new BigDecimal("-1000")),
                new DatedAmount(LocalDate.of(2026, 1, 1), new BigDecimal("900"))));
        assertThat(r).isPresent();
        assertThat(r.get()).isCloseTo(new BigDecimal("-0.10"), within(new BigDecimal("0.000001")));
    }

    @DisplayName("无解与退化：流数<2 或恒正 → empty")
    @Test
    void givenDegenerate_whenXirr_thenEmpty() {
        assertThat(IrrCalculator.xirr(List.of())).isEmpty();
        assertThat(IrrCalculator.xirr(List.of(
                new DatedAmount(LocalDate.of(2025, 1, 1), new BigDecimal("1000"))))).isEmpty();
        assertThat(IrrCalculator.xirr(List.of(
                new DatedAmount(LocalDate.of(2025, 1, 1), new BigDecimal("-1000")),
                new DatedAmount(LocalDate.of(2026, 1, 1), new BigDecimal("1950"))))).isPresent();  // r=0.95 在区间内
    }
}
