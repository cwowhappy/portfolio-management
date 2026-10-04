package com.portfolio.invest.domain.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 外部流日期归一：与 NavReconstructor 的计入规则对齐——流水在首个「event.date ≤ t」的
 * NAV 交易日生效（NavReconstructor.java 的 date ≤ t 累积循环），故计算器按「NAV 序列中
 * ≥ flowDate 的首个 tradeDate」归并：早于首日归一到首日，落在非交易日归一到下一交易日，
 * 晚于末日则忽略（尚未反映在任何 NAV 中，不影响本序列）。
 */
final class FlowDates {

    private FlowDates() {}

    /** 归一后的 tradeDate → 净流金额（同日的多笔已合并）；空序列返回空表 */
    static Map<LocalDate, BigDecimal> normalize(NavSeries series, List<ExternalFlow> flows) {
        List<DailyPoint> points = series.points();
        Map<LocalDate, BigDecimal> byDay = new TreeMap<>();
        for (ExternalFlow f : flows) {
            LocalDate target = null;
            for (DailyPoint p : points) {  // points 按 tradeDate 升序
                if (!p.tradeDate().isBefore(f.date())) {
                    target = p.tradeDate();
                    break;
                }
            }
            if (target != null) {
                byDay.merge(target, f.amount(), BigDecimal::add);
            }
        }
        return byDay;
    }
}
