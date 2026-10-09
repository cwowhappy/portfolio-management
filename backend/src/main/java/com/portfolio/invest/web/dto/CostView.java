package com.portfolio.invest.web.dto;

import com.portfolio.invest.application.observability.ObservabilityApplicationService;
import java.util.List;

/**
 * 成本看板视图（GET /api/admin/observability/cost，MS-30 B5 §7.1）：byDay 轮表 token
 * 消耗 + byTool 工具表调用统计。单价估算（unit-price-cny-per-mtok）为前端本地乘法不入库。
 */
public record CostView(List<DayUsage> byDay, List<ToolUsage> byTool) {

    /** 按日消耗桶（date 为上海时区 YYYY-MM-DD）。 */
    public record DayUsage(String date, long promptTokens, long completionTokens,
                           long totalTokens, long turns) {

        static DayUsage from(com.portfolio.invest.domain.observability.ObservabilityQueryRepository
                .DailyTokenUsage usage) {
            return new DayUsage(usage.date(), usage.promptTokens(), usage.completionTokens(),
                    usage.totalTokens(), usage.turns());
        }
    }

    /** 按工具调用统计（avgDurationMs null = 该工具全无时长记录）。 */
    public record ToolUsage(String tool, long calls, Double avgDurationMs) {

        static ToolUsage from(com.portfolio.invest.domain.observability.ObservabilityQueryRepository
                .ToolCallStat stat) {
            return new ToolUsage(stat.tool(), stat.calls(), stat.avgDurationMs());
        }
    }

    /** 用例聚合 → 视图。 */
    public static CostView from(ObservabilityApplicationService.Cost cost) {
        return new CostView(cost.byDay().stream().map(DayUsage::from).toList(),
                cost.byTool().stream().map(ToolUsage::from).toList());
    }
}
