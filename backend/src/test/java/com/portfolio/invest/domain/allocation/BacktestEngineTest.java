package com.portfolio.invest.domain.allocation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class BacktestEngineTest {

    /** 股 60/现 40，本金 1000；两日股票收益 +10%/+10%，现金 0。
     * 永不：1000→1060→1126；每 1 日再平衡：1060 → 重置 636/424 → 1123.6。 */
    @DisplayName("已知答案：永不再平衡 1126 vs 每日再平衡 1123.6（权重漂移效应）")
    @Test
    void givenTwoUpDays_whenNeverVsDailyRebalance_thenWeightDriftDiffers() {
        Map<AssetClass, List<BacktestEngine.DatedReturn>> rets = Map.of(
                AssetClass.STOCK, List.of(dr(1, "0.1"), dr(2, "0.1")),
                AssetClass.CASH, List.of(dr(1, "0"), dr(2, "0")));
        var weights = Map.of(AssetClass.STOCK, bd("60"), AssetClass.CASH, bd("40"));
        var never = BacktestEngine.run(weights, rets, 0);
        assertThat(never.points().get(2).value())
                .isCloseTo(bd("1126"), within(bd("0.000001")));
        var daily = BacktestEngine.run(weights, rets, 1);
        assertThat(daily.points().get(2).value())
                .isCloseTo(bd("1123.6"), within(bd("0.000001")));
    }

    @DisplayName("REITS 权重>0 → REITS_BACKTEST_UNSUPPORTED；空权重 → INVALID_INPUT")
    @Test
    void givenReitsOrEmpty_whenRun_thenRejects() {
        assertThatThrownBy(() -> BacktestEngine.run(
                Map.of(AssetClass.REITS, bd("10"), AssetClass.CASH, bd("90")),
                Map.of(AssetClass.CASH, List.of(dr(1, "0"))), 0))
                .isInstanceOf(AllocationException.class)
                .hasMessageContaining("REITs");
        assertThatThrownBy(() -> BacktestEngine.run(
                Map.of(), Map.of(AssetClass.CASH, List.of(dr(1, "0"))), 0))
                .isInstanceOf(AllocationException.class);
    }

    private static BacktestEngine.DatedReturn dr(int day, String ret) {
        return new BacktestEngine.DatedReturn(LocalDate.of(2026, 1, 4 + day), new BigDecimal(ret));
    }

    private static BigDecimal bd(String v) { return new BigDecimal(v); }
}
