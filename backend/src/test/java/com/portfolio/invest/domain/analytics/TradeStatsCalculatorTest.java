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
                List.of(new TradeStatsCalculator.BuyLot("600519", LocalDate.of(2026, 1, 5), new BigDecimal("100")),
                        new TradeStatsCalculator.BuyLot("600519", LocalDate.of(2026, 2, 4), new BigDecimal("200"))),
                List.of(new TradeStatsCalculator.SellLot("600519", LocalDate.of(2026, 3, 6), new BigDecimal("100"), new BigDecimal("500")),
                        new TradeStatsCalculator.SellLot("600519", LocalDate.of(2026, 4, 5), new BigDecimal("100"), new BigDecimal("-200"))));
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

    @DisplayName("已知答案：单股买→卖→买→卖交错——后笔买入不得污染早笔卖出的加权买入日")
    @Test
    void givenInterleavedBuySellBuySell_whenStats_thenHoldingDaysFollowChronology() {
        // 手算推演（e = 2026-01-05 的 epoch 天；02-05=e+31、03-05=e+59、04-05=e+90）：
        // ① 01-05 买100：Σq=100、Σ(q×e)=100e → 加权买入日 = e；
        // ② 02-05 卖50：持有₁ = 31−0 = 31 天（此刻 03-05 的买入尚未发生，不得参与）；
        //    扣减后 Σq=50、Σ(q×e)=50e；
        // ③ 03-05 买100：Σq=150、Σ(q×e)=150e+5900 → 加权买入日 = e+5900/150 = e+39.3333…；
        // ④ 04-05 卖100：持有₂ = round(90−39.3333) = 51 天；
        // 平均持有 = (31+51)/2 = 41；两笔盈亏 +200/−100。
        var s = TradeStatsCalculator.stats(
                List.of(new TradeStatsCalculator.BuyLot("600519", LocalDate.of(2026, 1, 5), new BigDecimal("100")),
                        new TradeStatsCalculator.BuyLot("600519", LocalDate.of(2026, 3, 5), new BigDecimal("100"))),
                List.of(new TradeStatsCalculator.SellLot("600519", LocalDate.of(2026, 2, 5), new BigDecimal("50"), new BigDecimal("200")),
                        new TradeStatsCalculator.SellLot("600519", LocalDate.of(2026, 4, 5), new BigDecimal("100"), new BigDecimal("-100"))));
        assertThat(s.sellCount()).isEqualTo(2);
        assertThat(s.winCount()).isEqualTo(1);
        assertThat(s.winRate()).isEqualByComparingTo("0.5");
        assertThat(s.avgWin()).isEqualByComparingTo("200");
        assertThat(s.avgLoss()).isEqualByComparingTo("100");
        assertThat(s.profitFactor()).isEqualByComparingTo("2");
        // 旧实现先累积全部买入（加权买入日=e+29.5）再逐卖扣减 → (1+60)/2=30.5，结构性错误
        assertThat(s.avgHoldingDays()).isEqualByComparingTo("41");
        assertThat(s.bestPnl()).isEqualByComparingTo("200");
        assertThat(s.worstPnl()).isEqualByComparingTo("-100");
    }

    @DisplayName("已知答案：双股混合——A/B 各自独立计算持有天数后再聚合")
    @Test
    void givenTwoStocksMixed_whenStats_thenPerStockHoldingDays() {
        // 手算推演（e = 2026-01-05 的 epoch 天）：
        // A 股：01-05 买100 → 02-05 卖100（+50），持有 = 31−0 = 31 天；
        // B 股：01-20（e+15）买100 → 03-05（e+59）卖100（−30），持有 = 59−15 = 44 天；
        // 聚合：平均持有 = (31+44)/2 = 37.5；胜率 50%；盈亏比 = 50/30 = 1.6667。
        // 旧实现无 stockCode 分组混池 → 加权买入日 = e+7.5，得 (23+50)/2=36.5，错误。
        var s = TradeStatsCalculator.stats(
                List.of(new TradeStatsCalculator.BuyLot("600519", LocalDate.of(2026, 1, 5), new BigDecimal("100")),
                        new TradeStatsCalculator.BuyLot("000858", LocalDate.of(2026, 1, 20), new BigDecimal("100"))),
                List.of(new TradeStatsCalculator.SellLot("600519", LocalDate.of(2026, 2, 5), new BigDecimal("100"), new BigDecimal("50")),
                        new TradeStatsCalculator.SellLot("000858", LocalDate.of(2026, 3, 5), new BigDecimal("100"), new BigDecimal("-30"))));
        assertThat(s.sellCount()).isEqualTo(2);
        assertThat(s.winCount()).isEqualTo(1);
        assertThat(s.winRate()).isEqualByComparingTo("0.5");
        assertThat(s.avgWin()).isEqualByComparingTo("50");
        assertThat(s.avgLoss()).isEqualByComparingTo("30");
        assertThat(s.profitFactor()).isEqualByComparingTo("1.6667");
        assertThat(s.avgHoldingDays()).isEqualByComparingTo("37.5");
        assertThat(s.bestPnl()).isEqualByComparingTo("50");
        assertThat(s.worstPnl()).isEqualByComparingTo("-30");
    }

    @DisplayName("无卖出 → 全零空统计；无亏损单 → 盈亏比为 null")
    @Test
    void givenEdgeCases_whenStats_thenDegenerate() {
        var none = TradeStatsCalculator.stats(
                List.of(new TradeStatsCalculator.BuyLot("600519", LocalDate.of(2026, 1, 5), new BigDecimal("100"))), List.of());
        assertThat(none.sellCount()).isZero();
        var onlyWin = TradeStatsCalculator.stats(
                List.of(new TradeStatsCalculator.BuyLot("600519", LocalDate.of(2026, 1, 5), new BigDecimal("100"))),
                List.of(new TradeStatsCalculator.SellLot("600519", LocalDate.of(2026, 2, 5), new BigDecimal("100"), new BigDecimal("50"))));
        assertThat(onlyWin.profitFactor()).isNull();
        assertThat(onlyWin.avgLoss()).isEqualByComparingTo("0");
    }
}
