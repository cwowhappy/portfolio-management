package com.portfolio.invest.application.research;

import java.math.BigDecimal;

/**
 * 策略草稿暂存命令（D13）：六字段均可空——DRAFT 态字段不完整可存，
 * 定稿校验（估值下限 &lt; 上限且均非空）在 finalizeDoc 显式动作时才拦截。
 */
public record SaveStrategyCommand(
        String thesis,
        BigDecimal valuationLow,
        BigDecimal valuationHigh,
        String positionPlan,
        String buyConditions,
        String riskNotes) {}
