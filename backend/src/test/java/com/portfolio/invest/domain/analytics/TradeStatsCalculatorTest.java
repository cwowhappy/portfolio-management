package com.portfolio.invest.domain.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class TradeStatsCalculatorTest {

    @DisplayName("已知答案：两买两卖——胜率50% 盈亏比2.5 平均持有55天")
    @Test
    void givenTwoRoundTrips_whenStats_thenHandCalc() {
        // 买：01-05×100、02-04×200（加权平均买入日=01-25，day20）；卖：03-06×100（+500，持有40天）、04-05×100（−200，持有70天）
        var s = TradeStatsCalculator.stats(
                List.of(new TradeStatsCalculator.BuyLot(LocalDate.of(2026, 1, 5), new BigDecimal("100")),
                        new TradeStatsCalculator.BuyLot(LocalDate.of(2026, 2, 4), new BigDecimal("200"))),
                List.of(new TradeStatsCalculator.SellLot(LocalDate.of(2026, 3, 6), new BigDecimal("100"), new BigDecimal("500")),
                        new TradeStatsCalculator.SellLot(LocalDate.of(2026, 4, 5), new BigDecimal("100"), new BigDecimal("-200"))));
        assertThat(s.sellCount()).isEqualTo(2);
        assertThat(s.winCount()).isEqualTo(1);
        assertThat(s.winRate()).isEqualByComparingTo("0.5");
        assertThat(s.avgWin()).isEqualByComparingTo("500");
        assertThat(s.avgLoss()).isEqualByComparingTo("200");
        assertThat(s.profitFactor()).isEqualByComparingTo("2.5");
        assertThat(s.avgHoldingDays()).isEqualByComparingTo("55");
        assertThat(s.bestPnl()).isEqualByComparingTo("500");
        assertThat(s.worstPnl()).isEqualByComparingTo("-200");
    }

    @DisplayName("无卖出 → 全零空统计；无亏损单 → 盈亏比为 null")
    @Test
    void givenEdgeCases_whenStats_thenDegenerate() {
        var none = TradeStatsCalculator.stats(
                List.of(new TradeStatsCalculator.BuyLot(LocalDate.of(2026, 1, 5), new BigDecimal("100"))), List.of());
        assertThat(none.sellCount()).isZero();
        var onlyWin = TradeStatsCalculator.stats(
                List.of(new TradeStatsCalculator.BuyLot(LocalDate.of(2026, 1, 5), new BigDecimal("100"))),
                List.of(new TradeStatsCalculator.SellLot(LocalDate.of(2026, 2, 5), new BigDecimal("100"), new BigDecimal("50"))));
        assertThat(onlyWin.profitFactor()).isNull();
        assertThat(onlyWin.avgLoss()).isEqualByComparingTo("0");
    }
}
