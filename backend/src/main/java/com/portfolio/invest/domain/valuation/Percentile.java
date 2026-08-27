package com.portfolio.invest.domain.valuation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** 历史分位：当前值在历史序列中的经验分布百分位（0~100），历史为空时返回 null（表示数据积累中）。 */
public final class Percentile {

    private Percentile() {}

    public static BigDecimal of(BigDecimal current, List<BigDecimal> history) {
        if (current == null || history == null || history.isEmpty()) {
            return null;
        }
        long below = history.stream().filter(v -> v.compareTo(current) < 0).count();
        return BigDecimal.valueOf(below * 100.0 / history.size()).setScale(2, RoundingMode.HALF_UP);
    }
}
