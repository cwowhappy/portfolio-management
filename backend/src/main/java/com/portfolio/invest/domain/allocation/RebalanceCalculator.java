package com.portfolio.invest.domain.allocation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 再平衡计算纯函数：阈值（任一类 |实际−目标| ≥ 5pp）与时间（频率周期满）触发 + 逐类买卖金额。 */
public final class RebalanceCalculator {

    /** 阈值触发口径：绝对百分点，固定 5pp（spec 澄清 #1，硬编码同模板库口径）。 */
    static final BigDecimal THRESHOLD_PP = new BigDecimal("5");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private RebalanceCalculator() {}

    public static RebalanceResult calculate(Map<AssetClass, BigDecimal> targetWeights,
                                            Map<AssetClass, BigDecimal> actualWeights,
                                            BigDecimal totalAssets,
                                            RebalanceFrequency frequency, Instant anchor, Instant today) {
        BigDecimal total = totalAssets == null ? BigDecimal.ZERO : totalAssets;
        boolean suppressed = total.signum() <= 0; // 空组合无可再平衡之物，全抑制

        List<RebalanceResult.Item> items = new ArrayList<>();
        boolean thresholdAlert = false;
        for (AssetClass ac : AssetClass.values()) {
            BigDecimal target = targetWeights.getOrDefault(ac, BigDecimal.ZERO);
            BigDecimal actual = actualWeights.getOrDefault(ac, BigDecimal.ZERO);
            BigDecimal deviation = actual.subtract(target);
            boolean breached = !suppressed && deviation.abs().compareTo(THRESHOLD_PP) >= 0;
            thresholdAlert |= breached;
            BigDecimal targetAmount = total.multiply(target).divide(HUNDRED, 2, RoundingMode.HALF_UP);
            BigDecimal currentAmount = total.multiply(actual).divide(HUNDRED, 2, RoundingMode.HALF_UP);
            items.add(new RebalanceResult.Item(ac, target, actual, deviation,
                    targetAmount, currentAmount, targetAmount.subtract(currentAmount), breached));
        }

        boolean timeTriggered = false;
        long daysOverdue = 0;
        if (!suppressed && frequency != null && frequency != RebalanceFrequency.OFF
                && anchor != null && frequency.days() > 0) {
            long elapsed = Duration.between(anchor, today).toDays();
            if (elapsed >= frequency.days()) {
                timeTriggered = true;
                daysOverdue = elapsed - frequency.days();
            }
        }
        return new RebalanceResult(List.copyOf(items),
                new RebalanceResult.TimeTrigger(timeTriggered, daysOverdue), suppressed,
                thresholdAlert || timeTriggered);
    }
}
