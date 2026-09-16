package com.portfolio.invest.domain.allocation;

import java.math.BigDecimal;
import java.util.List;

/** 再平衡计算结果：逐类偏离/金额建议 + 提醒触发状态（纯数据，无行为）。 */
public record RebalanceResult(
        List<Item> items, TimeTrigger timeTrigger, boolean suppressed, boolean anyAlert) {

    public record Item(AssetClass assetClass, BigDecimal targetWeight, BigDecimal actualWeight,
                       BigDecimal deviation, BigDecimal targetAmount, BigDecimal currentAmount,
                       BigDecimal suggestedAmount, boolean thresholdBreached) {}

    /** 时间提醒：triggered=锚点起已满周期；daysOverdue=超过周期的自然日数。 */
    public record TimeTrigger(boolean triggered, long daysOverdue) {}
}
