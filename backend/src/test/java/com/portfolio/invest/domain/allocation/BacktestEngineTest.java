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

    /** 规格 §七点名边界：全现金方案。CASH 两日各 +0.01%（0.0001）：
     * 1000 → 1000×1.0001=1000.1 → 1000.1×1.0001=1000.20001。单资产 100% 权重的
     * 再平衡重置是恒等变换（total×100/100=total），故 N=1 与永不两分支逐点相等。 */
    @DisplayName("已知答案：全现金方案 1000→1000.1→1000.20001，再平衡对单资产无差异")
    @Test
    void givenAllCashPlan_whenRun_thenKnownAnswerAndRebalanceInvariant() {
        Map<AssetClass, List<BacktestEngine.DatedReturn>> rets = Map.of(
                AssetClass.CASH, List.of(dr(1, "0.0001"), dr(2, "0.0001")));
        var weights = Map.of(AssetClass.CASH, bd("100"));
        var never = BacktestEngine.run(weights, rets, 0);
        assertThat(never.points()).hasSize(3); // 期初 1000（与首日收盘同日期）+ 两个交易日点
        assertThat(never.points().get(0).value()).isCloseTo(bd("1000"), within(bd("0.000001")));
        assertThat(never.points().get(1).value()).isCloseTo(bd("1000.1"), within(bd("0.000001")));
        assertThat(never.points().get(2).value()).isCloseTo(bd("1000.20001"), within(bd("0.000001")));
        var daily = BacktestEngine.run(weights, rets, 1);
        assertThat(daily.points()).isEqualTo(never.points()); // 单资产：重置权重=恒等，两分支曲线全等
    }

    @DisplayName("守卫：RebalanceMode 交易日映射 QUARTERLY=63 / ANNUAL=252 / NEVER=0（常量笔误即红）")
    @Test
    void givenRebalanceModes_whenTradingDays_thenExactConstantMapping() {
        assertThat(BacktestEngine.RebalanceMode.QUARTERLY.tradingDays()).isEqualTo(63);
        assertThat(BacktestEngine.RebalanceMode.ANNUAL.tradingDays()).isEqualTo(252);
        assertThat(BacktestEngine.RebalanceMode.NEVER.tradingDays()).isEqualTo(0);
    }

    private static BacktestEngine.DatedReturn dr(int day, String ret) {
        return new BacktestEngine.DatedReturn(LocalDate.of(2026, 1, 4 + day), new BigDecimal(ret));
    }

    private static BigDecimal bd(String v) { return new BigDecimal(v); }
}
