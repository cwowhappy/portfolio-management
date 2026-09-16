package com.portfolio.invest.domain.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** 年度收益：年内日 TWR 几何链接（spec 02-design §2.3）。 */
public final class AnnualReturnCalculator {

    private AnnualReturnCalculator() {}

    public static java.util.SortedMap<Integer, BigDecimal> yearlyTwr(NavSeries series, List<ExternalFlow> flows) {
        Map<LocalDate, BigDecimal> byDay = new TreeMap<>();
        for (ExternalFlow f : flows) {
            byDay.merge(f.date(), f.amount(), BigDecimal::add);
        }
        TreeSet<Integer> years = new TreeSet<>();
        series.points().forEach(p -> years.add(p.tradeDate().getYear()));
        java.util.SortedMap<Integer, BigDecimal> out = new TreeMap<>();
        for (int y : years) {
            List<DailyPoint> inYear = new ArrayList<>(series.points().stream()
                    .filter(p -> p.tradeDate().getYear() == y).toList());
            // 跨年点位只进后一年：上一上年末最后点位作为本年首段基准
            series.points().stream().filter(p -> p.tradeDate().getYear() < y)
                    .reduce((a, b) -> b)
                    .ifPresent(base -> inYear.add(0, base));
            BigDecimal growth = BigDecimal.ONE;
            for (int i = 1; i < inYear.size(); i++) {
                DailyPoint prev = inYear.get(i - 1);
                DailyPoint cur = inYear.get(i);
                if (prev.totalValue().signum() <= 0) {
                    continue;
                }
                BigDecimal f = byDay.getOrDefault(cur.tradeDate(), BigDecimal.ZERO);
                // (V_t−F_t)/V_{t−1} 即 1+R，直接作为链接因子（与 TwrCalculator 口径一致）
                growth = growth.multiply(cur.totalValue().subtract(f).divide(prev.totalValue(),
                        new java.math.MathContext(20, java.math.RoundingMode.HALF_UP)));
            }
            out.put(y, growth.subtract(BigDecimal.ONE));
        }
        return out;
    }
}
