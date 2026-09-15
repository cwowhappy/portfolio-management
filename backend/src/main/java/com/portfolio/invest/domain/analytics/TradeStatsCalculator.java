package com.portfolio.invest.domain.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** 交易统计：胜率/盈亏比/平均持有天数/最佳最差（加权平均口径，spec 02-design §2.3）。 */
public final class TradeStatsCalculator {

    private TradeStatsCalculator() {}

    public record BuyLot(LocalDate date, BigDecimal quantity) {}
    public record SellLot(LocalDate date, BigDecimal quantity, BigDecimal realizedPnl) {}
    public record TradeStats(int sellCount, int winCount, BigDecimal winRate, BigDecimal avgWin,
            BigDecimal avgLoss, BigDecimal profitFactor, BigDecimal avgHoldingDays,
            BigDecimal bestPnl, BigDecimal worstPnl) {}

    public static TradeStats stats(List<BuyLot> buys, List<SellLot> sells) {
        int win = 0;
        BigDecimal sumWin = BigDecimal.ZERO, sumLoss = BigDecimal.ZERO;
        BigDecimal best = null, worst = null;
        // 加权平均买入日（epoch 天）：买增 Σ(q×e) 与 Σq；卖按当日均价扣减（均价不变，镜像加权平均成本）
        double sumQtyEpoch = 0, sumQty = 0;
        BigDecimal sumHoldDays = BigDecimal.ZERO;
        for (BuyLot b : buys) {
            double e = b.date().toEpochDay();
            sumQtyEpoch += b.quantity().doubleValue() * e;
            sumQty += b.quantity().doubleValue();
        }
        for (SellLot s : sells) {
            double avgEpoch = sumQty > 0 ? sumQtyEpoch / sumQty : s.date().toEpochDay();
            double q = s.quantity().doubleValue();
            sumQtyEpoch -= q * avgEpoch;
            sumQty -= q;
            sumHoldDays = sumHoldDays.add(BigDecimal.valueOf(
                    ChronoUnit.DAYS.between(LocalDate.ofEpochDay((long) Math.floor(avgEpoch)), s.date())
                            - (avgEpoch % 1.0 >= 0.5 ? 1 : 0)));
            BigDecimal pnl = s.realizedPnl();
            if (pnl.signum() > 0) {
                win++;
                sumWin = sumWin.add(pnl);
            } else {
                sumLoss = sumLoss.add(pnl.abs());
            }
            best = best == null || pnl.compareTo(best) > 0 ? pnl : best;
            worst = worst == null || pnl.compareTo(worst) < 0 ? pnl : worst;
        }
        int n = sells.size();
        BigDecimal avgWin = n == 0 ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                : sumWin.divide(BigDecimal.valueOf(win == 0 ? 1 : win), 4, RoundingMode.HALF_UP);
        BigDecimal avgLoss = n == 0 ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                : sumLoss.divide(BigDecimal.valueOf(n - win == 0 ? 1 : n - win), 4, RoundingMode.HALF_UP);
        BigDecimal profitFactor = sumLoss.signum() == 0 ? null
                : sumWin.divide(sumLoss, 4, RoundingMode.HALF_UP);
        return new TradeStats(n, win,
                n == 0 ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                        : BigDecimal.valueOf(win).divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP),
                avgWin, avgLoss, profitFactor,
                n == 0 ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                        : sumHoldDays.divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP),
                best == null ? BigDecimal.ZERO : best, worst == null ? BigDecimal.ZERO : worst);
    }
}
