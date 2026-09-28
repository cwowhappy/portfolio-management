package com.portfolio.invest.domain.research;

import java.math.BigDecimal;
import java.util.List;

/**
 * 纪律检查上下文（P3-T4 组装）：计划仓位比值（建仓/加减仓意图）+ 当前估值倍数（行情快照）+
 * F01 必查项勾选集合 + 证伪条件集（SELL/REDUCE 核对用）。
 * 数值字段可空——缺失指标的规则项跳过比较（见 {@link DisciplineCheckService}）；
 * 列表字段 null 容错（视同空集合）。
 */
public record CheckContext(BigDecimal plannedSingleRatio, BigDecimal plannedIndustryRatio,
                           BigDecimal currentPe, BigDecimal currentPb,
                           List<String> f01MustItems, List<Falsifier> falsifiers) {

    public CheckContext {
        f01MustItems = f01MustItems == null ? List.of() : List.copyOf(f01MustItems);
        falsifiers = falsifiers == null ? List.of() : List.copyOf(falsifiers);
    }
}
