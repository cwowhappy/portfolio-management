package com.portfolio.invest.domain.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeSet;

/** 每日净值重建：流水事件 × 收盘价 → 逐交易日总资产序列（读侧重算，spec 02-design §2.2）。 */
public final class NavReconstructor {

    private NavReconstructor() {}

    public static NavSeries reconstruct(LocalDate start, LocalDate end,
            List<StockEvent> stockEvents, List<CashEvent> cashEvents,
            Map<String, SortedMap<LocalDate, BigDecimal>> closes) {
        // 1) 序列日期 = 全部 close 日期并集 ∩ [start,end]
        TreeSet<LocalDate> days = new TreeSet<>();
        closes.values().forEach(m -> m.keySet().stream()
                .filter(d -> !d.isBefore(start) && !d.isAfter(end)).forEach(days::add));
        if (days.isEmpty()) {
            return new NavSeries(List.of());
        }
        // 2) 逐事件累积：按日期排序后，「date ≤ t」全部生效
        List<StockEvent> se = stockEvents.stream().sorted(java.util.Comparator.comparing(StockEvent::date)).toList();
        List<CashEvent> ce = cashEvents.stream().sorted(java.util.Comparator.comparing(CashEvent::date)).toList();

        List<DailyPoint> points = new ArrayList<>();
        java.util.Map<String, BigDecimal> qty = new java.util.HashMap<>();
        java.util.Map<String, BigDecimal> lastClose = new java.util.HashMap<>();
        BigDecimal cash = BigDecimal.ZERO;
        int si = 0, ci = 0;
        for (LocalDate t : days) {
            while (si < se.size() && !se.get(si).date().isAfter(t)) {
                qty.merge(se.get(si).stockCode(), se.get(si).qtyDelta(), BigDecimal::add);
                si++;
            }
            while (ci < ce.size() && !ce.get(ci).date().isAfter(t)) {
                cash = cash.add(ce.get(ci).amountDelta());
                ci++;
            }
            BigDecimal mv = BigDecimal.ZERO;
            for (var e : qty.entrySet()) {
                SortedMap<LocalDate, BigDecimal> src = closes.getOrDefault(e.getKey(), new java.util.TreeMap<>());
                java.util.NavigableMap<LocalDate, BigDecimal> m = (src instanceof java.util.NavigableMap<LocalDate, BigDecimal> nav)
                        ? nav : new java.util.TreeMap<>(src);
                Map.Entry<LocalDate, BigDecimal> floor = m.floorEntry(t);   // forward-fill
                if (floor != null) {
                    lastClose.put(e.getKey(), floor.getValue());
                }
                BigDecimal px = lastClose.get(e.getKey());
                if (px != null) {
                    mv = mv.add(px.multiply(e.getValue()));
                }
            }
            points.add(new DailyPoint(t, mv, cash, mv.add(cash)));
        }
        return new NavSeries(List.copyOf(points));
    }
}
