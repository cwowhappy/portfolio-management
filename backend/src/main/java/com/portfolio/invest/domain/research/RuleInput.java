package com.portfolio.invest.domain.research;

import java.math.BigDecimal;

/**
 * 纪律规则输入值对象（NFR-4 落点）：domain 不 import domain.wiki，wiki PrincipleRule → RuleInput
 * 的转换发生在 application 层（P3-T4）。metric 取 {@code PrincipleMetric} 枚举名逐字一致
 * （SINGLE_POSITION_RATIO / INDUSTRY_POSITION_RATIO / STOCK_PE_MAX / STOCK_PB_MAX），
 * 启用过滤（enabled）也在转换侧完成——本列表只含启用规则。
 */
public record RuleInput(String metric, BigDecimal threshold) {

    public RuleInput {
        if (metric == null || metric.isBlank()) {
            throw new ResearchException(ResearchErrorCode.METRIC_REQUIRED, "纪律规则指标不能为空");
        }
        if (threshold == null) {
            throw new ResearchException(ResearchErrorCode.THRESHOLD_INVALID, "纪律规则阈值不能为空");
        }
    }
}
