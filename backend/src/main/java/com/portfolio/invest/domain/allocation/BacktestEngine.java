package com.portfolio.invest.domain.allocation;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 配置回测引擎（MS-13 M07-F06，spec §2.5）：四类资产（股/债/金/现金）日频份额制重放，
 * 再平衡日重置目标权重（份额=金额/当日资产净值，简化为金额直乘）。「简易」边界：
 * 无费用/税/滑点；REITS 不支持（无数据源）。输出曲线首日本金 1000。
 */
public final class BacktestEngine {

    public static final String REITS_BACKTEST_UNSUPPORTED = "REITS_BACKTEST_UNSUPPORTED";
    public static final String INVALID_INPUT = "INVALID_INPUT";

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private static final BigDecimal INITIAL_CAPITAL = BigDecimal.valueOf(1000);

    private BacktestEngine() {}

    public record DatedReturn(LocalDate date, BigDecimal ret) {}

    public enum RebalanceMode {
        NEVER(0), QUARTERLY(63), ANNUAL(252);

        private final int tradingDays;

        RebalanceMode(int tradingDays) { this.tradingDays = tradingDays; }

        public int tradingDays() { return tradingDays; }
    }

    public record CurvePoint(LocalDate date, BigDecimal value) {}

    public record BacktestCurve(List<CurvePoint> points) {}

    /** weights 百分数（Σ≈100）；returns 各资产**日期对齐**的日收益序列（小数）；rebalanceEveryTradingDays 0=永不。 */
    public static BacktestCurve run(Map<AssetClass, BigDecimal> weights,
                                    Map<AssetClass, List<DatedReturn>> returns,
                                    int rebalanceEveryTradingDays) {
        if (weights == null || weights.isEmpty()) {
            throw new AllocationException(INVALID_INPUT, "回测权重为空");
        }
        BigDecimal reits = weights.getOrDefault(AssetClass.REITS, BigDecimal.ZERO);
        if (reits.signum() > 0) {
            throw new AllocationException(REITS_BACKTEST_UNSUPPORTED,
                    "REITs 无回测数据源，请将 REITs 权重置 0 后重试");
        }
        List<AssetClass> assets = weights.keySet().stream()
                .filter(a -> weights.get(a).signum() > 0).toList();
        if (assets.isEmpty() || assets.stream().anyMatch(a -> returns.get(a) == null || returns.get(a).isEmpty())) {
            throw new AllocationException(INVALID_INPUT, "存在无收益序列的资产，无法回测");
        }
        // 日期取各资产序列交集（升序）
        TreeSet<LocalDate> dates = new TreeSet<>(returns.get(assets.get(0)).stream()
                .map(DatedReturn::date).toList());
        for (AssetClass a : assets) {
            dates.retainAll(returns.get(a).stream().map(DatedReturn::date).toList());
        }
        // 份额制：期初按目标权重拆 1000 本金
        Map<AssetClass, BigDecimal> value = new java.util.LinkedHashMap<>();
        for (AssetClass a : assets) {
            value.put(a, INITIAL_CAPITAL.multiply(weights.get(a), MC)
                    .divide(BigDecimal.valueOf(100), MC));
        }
        List<CurvePoint> points = new ArrayList<>();
        // 首点=期初本金（标签 dates.first()，视为该日开盘前；当日收益在循环内计入）——
        // 因此首日会出现两个同日期点（期初 1000 + 收盘 1060），是本引擎的既定输出约定
        points.add(new CurvePoint(dates.first(), sum(value)));
        int day = 0;
        for (LocalDate d : dates) {
            for (AssetClass a : assets) {
                value.put(a, value.get(a).multiply(
                        BigDecimal.ONE.add(retOf(returns.get(a), d), MC), MC));
            }
            day++;
            if (rebalanceEveryTradingDays > 0 && day % rebalanceEveryTradingDays == 0) {
                BigDecimal total = sum(value);
                for (AssetClass a : assets) {
                    value.put(a, total.multiply(weights.get(a), MC)
                            .divide(BigDecimal.valueOf(100), MC));
                }
            }
            points.add(new CurvePoint(d, sum(value).setScale(10, RoundingMode.HALF_UP)));
        }
        return new BacktestCurve(points);
    }

    private static BigDecimal retOf(List<DatedReturn> series, LocalDate d) {
        for (DatedReturn r : series) {
            if (r.date().equals(d)) {
                return r.ret();
            }
        }
        return BigDecimal.ZERO;  // 交集已保证存在，防御
    }

    private static BigDecimal sum(Map<AssetClass, BigDecimal> value) {
        return value.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
