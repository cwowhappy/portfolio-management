package com.portfolio.invest.domain.industry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** 窗口化历史分位：当前值在窗口序列经验分布中的百分位（0~100，2 位 HALF_UP，语义对齐估值域 Percentile）。 */
public final class WindowedPercentile {

    /** 序列非空值不足该门槛（约一年交易日）时返回 null——「数据积累中」，不输出误导性分位。 */
    public static final int MIN_SERIES_DAYS = 250;

    private WindowedPercentile() {}

    public static BigDecimal of(BigDecimal current, List<BigDecimal> windowSeries) {
        if (current == null || windowSeries == null || windowSeries.isEmpty()) {
            return null;
        }
        long below = 0;
        long size = 0;
        for (BigDecimal v : windowSeries) {
            if (v == null) continue;
            size++;
            if (v.compareTo(current) < 0) below++;
        }
        if (size < MIN_SERIES_DAYS) {
            return null;
        }
        return BigDecimal.valueOf(below)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(size), 2, RoundingMode.HALF_UP);
    }
}
