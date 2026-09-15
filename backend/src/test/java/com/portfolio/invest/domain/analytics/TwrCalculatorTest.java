package com.portfolio.invest.domain.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TwrCalculatorTest {

    private static NavSeries series(double... totals) {
        LocalDate d = LocalDate.of(2026, 1, 5);
        List<DailyPoint> pts = new java.util.ArrayList<>();
        for (double v : totals) {
            pts.add(new DailyPoint(d, BigDecimal.ZERO, BigDecimal.valueOf(v), BigDecimal.valueOf(v)));
            d = d.plusDays(1);
        }
        return new NavSeries(pts);
    }

    @DisplayName("已知答案：无窗口内现金流 1.1×(12/11)=1.2 → 累计 20%")
    @Test
    void givenNoFlowInsideWindow_whenCumulative_thenTwentyPercent() {
        BigDecimal r = TwrCalculator.cumulative(series(1000, 1100, 1200), List.of());
        assertThat(r).isCloseTo(new BigDecimal("0.2"), within(new BigDecimal("0.000001")));
    }

    @DisplayName("已知答案：D2 日初转入 500 → 累计 1.1×1.0625−1=16.875%")
    @Test
    void givenMorningDepositOnD2_whenCumulative_thenSixteenEightSevenFive() {
        BigDecimal r = TwrCalculator.cumulative(series(1000, 1600, 1700),
                List.of(new ExternalFlow(LocalDate.of(2026, 1, 6), new BigDecimal("500"))));
        assertThat(r).isCloseTo(new BigDecimal("0.16875"), within(new BigDecimal("0.000001")));
    }

    @DisplayName("流出为负 F：D2 提走 500（V=1100−500=600）→ R2=(600+500)/1000=1.1")
    @Test
    void givenWithdrawOnD2_whenCumulative_thenSameAsDeposit() {
        BigDecimal r = TwrCalculator.cumulative(series(1000, 600, 660),
                List.of(new ExternalFlow(LocalDate.of(2026, 1, 6), new BigDecimal("-500"))));
        // R2=(600−(−500))/1000=1.1；R3=660/600=1.1 → 累计 21%
        assertThat(r).isCloseTo(new BigDecimal("0.21"), within(new BigDecimal("0.000001")));
    }

    @DisplayName("单点序列与年化退化为 0")
    @Test
    void givenSinglePoint_whenCumulative_thenZero() {
        assertThat(TwrCalculator.cumulative(series(1000), List.of()))
                .isEqualByComparingTo("0");
    }

    @DisplayName("年化：365 天窗口=累计本身；730 天=1.2^0.5−1")
    @Test
    void givenCumulative_whenAnnualized_thenMatchesCompound() {
        assertThat(TwrCalculator.annualized(new BigDecimal("0.2"), 365))
                .isCloseTo(new BigDecimal("0.2"), within(new BigDecimal("0.000001")));
        assertThat(TwrCalculator.annualized(new BigDecimal("0.2"), 730))
                .isCloseTo(new BigDecimal("0.095445"), within(new BigDecimal("0.000001")));
    }
}
