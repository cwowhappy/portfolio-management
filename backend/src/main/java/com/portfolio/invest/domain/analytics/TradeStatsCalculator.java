package com.portfolio.invest.domain.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 交易统计：胜率/盈亏比/平均持有天数/最佳最差（加权平均口径，spec 02-design §2.3）。 */
public final class TradeStatsCalculator {

    private TradeStatsCalculator() {}

    public record BuyLot(String stockCode, LocalDate date, BigDecimal quantity) {}
    public record SellLot(String stockCode, LocalDate date, BigDecimal quantity, BigDecimal realizedPnl) {}
    public record TradeStats(int sellCount, int winCount, BigDecimal winRate, BigDecimal avgWin,
            BigDecimal avgLoss, BigDecimal profitFactor, BigDecimal avgHoldingDays,
            BigDecimal bestPnl, BigDecimal worstPnl) {}

    /** 组内时序事件：买/卖二选一（sell 非 null 即卖）；同日买先于卖，对齐写侧同日交易序。 */
    private record TimedLot(LocalDate date, boolean buy, BigDecimal quantity, SellLot sell) {}

    public static TradeStats stats(List<BuyLot> buys, List<SellLot> sells) {
        int win = 0;
        BigDecimal sumWin = BigDecimal.ZERO, sumLoss = BigDecimal.ZERO;
        BigDecimal best = null, worst = null;
        long sumHoldDays = 0;
        // 按 stockCode 分组（多持仓不混池），组内按日期时序重放：
        // 买事件累积 Σ(q×epoch) 与 Σq；卖事件先按当前加权平均买入日计持有天数再扣减
        // （镜像加权平均成本的滚动语义——后笔买入不再污染早笔卖出）。
        Map<String, List<TimedLot>> groups = new LinkedHashMap<>();
        for (BuyLot b : buys) {
            groups.computeIfAbsent(b.stockCode(), k -> new ArrayList<>())
                    .add(new TimedLot(b.date(), true, b.quantity(), null));
        }
        for (SellLot s : sells) {
            groups.computeIfAbsent(s.stockCode(), k -> new ArrayList<>())
                    .add(new TimedLot(s.date(), false, s.quantity(), s));
        }
        for (List<TimedLot> flow : groups.values()) {
            flow.sort(Comparator.comparing(TimedLot::date).thenComparingInt(t -> t.buy() ? 0 : 1));
            double sumQtyEpoch = 0, sumQty = 0;
            for (TimedLot e : flow) {
                double epoch = e.date().toEpochDay();
                double q = e.quantity().doubleValue();
                if (e.buy()) {
                    sumQtyEpoch += q * epoch;
                    sumQty += q;
                    continue;
                }
                double avgEpoch = sumQty > 0 ? sumQtyEpoch / sumQty : epoch;
                sumQtyEpoch -= q * avgEpoch;
                sumQty -= q;
                // 持有天数 = 卖出日 − 数量加权平均买入日，四舍五入取整
                sumHoldDays += Math.round(epoch - avgEpoch);
                BigDecimal pnl = e.sell().realizedPnl();
                if (pnl.signum() > 0) {
                    win++;
                    sumWin = sumWin.add(pnl);
                } else {
                    sumLoss = sumLoss.add(pnl.abs());
                }
                best = best == null || pnl.compareTo(best) > 0 ? pnl : best;
                worst = worst == null || pnl.compareTo(worst) < 0 ? pnl : worst;
            }
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
                        : BigDecimal.valueOf(sumHoldDays).divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP),
                best == null ? BigDecimal.ZERO : best, worst == null ? BigDecimal.ZERO : worst);
    }
}
