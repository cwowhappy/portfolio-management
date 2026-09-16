package com.portfolio.invest.application.allocation;

import com.portfolio.invest.domain.allocation.AllocationPlan;
import com.portfolio.invest.domain.allocation.AssetClass;
import com.portfolio.invest.domain.allocation.RebalanceFrequency;
import com.portfolio.invest.domain.allocation.RebalanceResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 再平衡视图：提醒状态 + 逐类买卖金额建议（页内提醒卡与导航红点共用）。 */
public record RebalanceView(
        boolean hasActivePlan, BigDecimal totalAssets, boolean suppressed,
        List<Item> items, TimeTriggerView timeTrigger, boolean anyAlert) {

    public record Item(AssetClass assetClass, BigDecimal targetWeight, BigDecimal actualWeight,
                       BigDecimal deviation, BigDecimal targetAmount, BigDecimal currentAmount,
                       BigDecimal suggestedAmount, boolean thresholdBreached) {}

    public record TimeTriggerView(RebalanceFrequency frequency, Instant anchorDate, Instant dueDate,
                                  long daysOverdue, boolean triggered) {}

    public static RebalanceView empty() {
        return new RebalanceView(false, BigDecimal.ZERO, false, List.of(), null, false);
    }

    public static RebalanceView from(AllocationPlan plan, RebalanceResult result, BigDecimal totalAssets) {
        List<Item> items = result.items().stream()
                .map(i -> new Item(i.assetClass(), i.targetWeight(), i.actualWeight(), i.deviation(),
                        i.targetAmount(), i.currentAmount(), i.suggestedAmount(), i.thresholdBreached()))
                .toList();
        // 始终吐 timeTrigger（含 OFF）：前端「上次再平衡」锚点展示不依赖频率开关；
        // OFF 时 dueDate 为 null、triggered 恒 false（calculator 已保证）。
        RebalanceFrequency frequency = plan.rebalanceFrequency() == null
                ? RebalanceFrequency.OFF : plan.rebalanceFrequency();
        Instant anchor = plan.lastRebalancedAt();
        Instant due = anchor == null || frequency == RebalanceFrequency.OFF ? null
                : anchor.plus(java.time.Duration.ofDays(frequency.days()));
        TimeTriggerView timeTrigger = new TimeTriggerView(frequency, anchor, due,
                result.timeTrigger().daysOverdue(), result.timeTrigger().triggered());
        return new RebalanceView(true, totalAssets, result.suppressed(), items, timeTrigger, result.anyAlert());
    }
}
