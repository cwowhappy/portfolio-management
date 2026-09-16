package com.portfolio.invest.domain.analytics;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** TWR：日频子周期几何链接 Π(1+R_t)−1，R_t=(V_t−F_t)/V_{t−1}−1（现金流日初到账），spec 02-design §2.3。 */
public final class TwrCalculator {

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

    private TwrCalculator() {}

    public static BigDecimal cumulative(NavSeries series, List<ExternalFlow> flows) {
        if (series.points().size() < 2) {
            return BigDecimal.ZERO.setScale(10, RoundingMode.HALF_UP);
        }
        Map<LocalDate, BigDecimal> byDay = new TreeMap<>();
        for (ExternalFlow f : flows) {
            byDay.merge(f.date(), f.amount(), BigDecimal::add);
        }
        BigDecimal growth = BigDecimal.ONE;
        for (int i = 1; i < series.points().size(); i++) {
            DailyPoint prev = series.points().get(i - 1);
            DailyPoint cur = series.points().get(i);
            BigDecimal f = byDay.getOrDefault(cur.tradeDate(), BigDecimal.ZERO);
            if (prev.totalValue().signum() <= 0) {
                continue;  // 前值为 0（空仓且无现金）无收益可言，跳过该日
            }
            BigDecimal r = cur.totalValue().subtract(f)
                    .divide(prev.totalValue(), MC)
                    .subtract(BigDecimal.ONE);
            growth = growth.multiply(BigDecimal.ONE.add(r, MC), MC);
        }
        return growth.subtract(BigDecimal.ONE).setScale(10, RoundingMode.HALF_UP);
    }

    public static BigDecimal annualized(BigDecimal cumulative, long windowDays) {
        if (windowDays <= 0) {
            return BigDecimal.ZERO.setScale(10, RoundingMode.HALF_UP);
        }
        double base = cumulative.add(BigDecimal.ONE).doubleValue();
        double v = Math.pow(base, 365.0 / windowDays) - 1.0;
        return BigDecimal.valueOf(v).setScale(10, RoundingMode.HALF_UP);
    }
}
