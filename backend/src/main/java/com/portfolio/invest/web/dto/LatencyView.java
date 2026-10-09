package com.portfolio.invest.web.dto;

import com.portfolio.invest.application.observability.ObservabilityApplicationService;
import java.util.List;

/**
 * 时延看板视图（GET /api/admin/observability/latency，MS-30 B5 §7.1）：
 * {turn: {p50Ms, p95Ms, byDay[]}, tool: {byTool[]}}——percentile_cont 直出值。
 */
public record LatencyView(TurnLatency turn, ToolLatency tool) {

    /** 轮整体 + 按日百分位。 */
    public record TurnLatency(Double p50Ms, Double p95Ms, List<DayPercentile> byDay) {}

    /** 按日轮时延百分位与轮数。 */
    public record DayPercentile(String date, Double p50Ms, Double p95Ms, long turns) {

        static DayPercentile from(
                com.portfolio.invest.domain.observability.ObservabilityQueryRepository.DailyLatencyStat stat) {
            return new DayPercentile(stat.date(), stat.p50Ms(), stat.p95Ms(), stat.turns());
        }
    }

    /** 工具侧聚合容器（§7.1 形状：tool 下仅 byTool）。 */
    public record ToolLatency(List<ToolPercentile> byTool) {}

    /** 按工具时延百分位与调用数。 */
    public record ToolPercentile(String tool, Double p50Ms, Double p95Ms, long calls) {

        static ToolPercentile from(
                com.portfolio.invest.domain.observability.ObservabilityQueryRepository.ToolLatencyStat stat) {
            return new ToolPercentile(stat.tool(), stat.p50Ms(), stat.p95Ms(), stat.calls());
        }
    }

    /** 用例聚合 → 视图。 */
    public static LatencyView from(ObservabilityApplicationService.Latency latency) {
        return new LatencyView(
                new TurnLatency(latency.turn().p50Ms(), latency.turn().p95Ms(),
                        latency.byDay().stream().map(DayPercentile::from).toList()),
                new ToolLatency(latency.byTool().stream().map(ToolPercentile::from).toList()));
    }
}
