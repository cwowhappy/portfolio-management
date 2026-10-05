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

    /**
     * B5 重构（retOf 预索引线性化）的 characterization 基线：2 资产 × 3 日已知收益
     * （股 70/现 30；股 +5%/-2%/+10%，现日 +0.01%），每日与每 3 日再平衡两档。
     * 期望值取自重构前现实现的逐点输出（toPlainString 逐字节），重构后必须逐字节一致。
     */
    @DisplayName("characterization：每日/每3日再平衡曲线与重构前逐字节一致")
    @Test
    void givenTwoAssetsThreeDays_whenRunDailyAndEvery3Days_thenCurvesMatchPreRefactor() {
        Map<AssetClass, List<BacktestEngine.DatedReturn>> rets = Map.of(
                AssetClass.STOCK, List.of(dr(1, "0.05"), dr(2, "-0.02"), dr(3, "0.10")),
                AssetClass.CASH, List.of(dr(1, "0.0001"), dr(2, "0.0001"), dr(3, "0.0001")));
        var weights = Map.of(AssetClass.STOCK, bd("70"), AssetClass.CASH, bd("30"));

        assertThat(dump(BacktestEngine.run(weights, rets, 1))).containsExactly(
                "2026-01-05=1000",
                "2026-01-05=1035.0300000000",
                "2026-01-06=1020.5706309000",
                "2026-01-07=1092.0411921819");
        assertThat(dump(BacktestEngine.run(weights, rets, 3))).containsExactly(
                "2026-01-05=1000",
                "2026-01-05=1035.0300000000",
                "2026-01-06=1020.3600030000",
                "2026-01-07=1092.4200090003");
    }

    /**
     * 边界语义固化：① 同日重复收益点取首点（线性扫描 first-match，1035.03 对应股 0.05
     * 而非重复点 0.99）；② 某资产独有的日期被交集剔除（01-08 无现金点位 → 不入曲线）。
     */
    @DisplayName("重复日期取首点、独有日期被交集剔除")
    @Test
    void givenDuplicateDateAndExtraDate_whenRun_thenFirstMatchWinsAndExtraDropped() {
        Map<AssetClass, List<BacktestEngine.DatedReturn>> dup = Map.of(
                AssetClass.STOCK, List.of(dr(1, "0.05"), dr(2, "-0.02"), dr(1, "0.99"), dr(4, "0.5")),
                AssetClass.CASH, List.of(dr(1, "0.0001"), dr(2, "0.0001"), dr(3, "0.0001")));
        var weights = Map.of(AssetClass.STOCK, bd("70"), AssetClass.CASH, bd("30"));

        assertThat(dump(BacktestEngine.run(weights, dup, 0))).containsExactly(
                "2026-01-05=1000",
                "2026-01-05=1035.0300000000",
                "2026-01-06=1020.3600030000");
    }

    /** 防御分支固化：经 run 的日期交集后本不可达，直接断言查表兜底仍为 0（语义不变）。 */
    @DisplayName("查表兜底：缺资产或缺日期一律按 0 收益处理")
    @Test
    void givenMissingAssetOrMissingDate_whenRetOf_thenZero() {
        Map<AssetClass, Map<LocalDate, BigDecimal>> idx = Map.of(
                AssetClass.STOCK, Map.of(LocalDate.of(2026, 1, 5), bd("0.05")));
        assertThat(BacktestEngine.retOf(idx, AssetClass.STOCK, LocalDate.of(2026, 1, 5)))
                .isEqualByComparingTo("0.05");
        // 缺日期 → 0
        assertThat(BacktestEngine.retOf(idx, AssetClass.STOCK, LocalDate.of(2026, 1, 6)))
                .isEqualByComparingTo(BigDecimal.ZERO);
        // 缺资产 → 0
        assertThat(BacktestEngine.retOf(idx, AssetClass.CASH, LocalDate.of(2026, 1, 5)))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    private static List<String> dump(BacktestEngine.BacktestCurve curve) {
        return curve.points().stream()
                .map(p -> p.date() + "=" + p.value().toPlainString())
                .toList();
    }

    private static BacktestEngine.DatedReturn dr(int day, String ret) {
        return new BacktestEngine.DatedReturn(LocalDate.of(2026, 1, 4 + day), new BigDecimal(ret));
    }

    private static BigDecimal bd(String v) { return new BigDecimal(v); }
}
