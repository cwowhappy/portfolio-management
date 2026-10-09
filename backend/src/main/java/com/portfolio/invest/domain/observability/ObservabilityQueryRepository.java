package com.portfolio.invest.domain.observability;

import java.time.Instant;
import java.util.List;

/**
 * 观测两表（tool_invocation_obs / turn_observation，V5）看板查询端口——MS-30 B5 起
 * 落库写入方是 Task 8/9 的 {@code ObservabilityRecorder}（写端口另行声明），本端口只承
 * admin 看板读路径（trace 分页筛选 / cost 按日按工具 / latency percentile）。实现走
 * infrastructure/persistence/observability 的 JdbcTemplate（风格 A，沿 EvalRunRepository
 * 形态——聚合无 ORM 需求，不建 JPA 门面）。
 *
 * <p>口径约定：
 * <ul>
 *   <li>「按日」桶键取 <b>Asia/Shanghai</b> 日界（产品/调度时区一致），值为 {@code YYYY-MM-DD}；</li>
 *   <li>percentile 走 PG {@code percentile_cont}（离散有序集线性插值），空集/全 null 时长
 *       返回 null 而非 0（前端区分「无数据」与「0 毫秒」）；</li>
 *   <li>avg/percentile 均<b>忽略 null 时长行</b>（观测旁路允许时长缺失），calls/turns 计数
 *       仍含这些行。</li>
 * </ul>
 */
public interface ObservabilityQueryRepository {

    /**
     * 工具调用明细倒序分页（called_at DESC, id DESC）。
     *
     * @param from   called_at 下界（含），null = 不限
     * @param to     called_at 上界（不含），null = 不限
     * @param tool   工具名精确筛选，null/空白 = 不限
     * @param failed 失败筛选（true 仅失败 / false 仅成功），null = 不限
     * @param page   页号（0 起）
     * @param size   页大小
     */
    TracePage findTrace(Instant from, Instant to, String tool, Boolean failed, int page, int size);

    /** 按日 token 消耗（turn_observation 汇总：token 求和 + 轮数），since 起、日期升序。 */
    List<DailyTokenUsage> tokenUsageByDay(Instant since);

    /** 按工具调用统计（tool_invocation_obs：calls 计数 + 非空时长均值），工具名升序。 */
    List<ToolCallStat> toolCallStats(Instant since);

    /** 轮时延整体百分位（turn_observation.duration_ms）；空集返回 null 分量。 */
    TurnLatencyStat turnLatency(Instant since);

    /** 轮时延按日百分位 + 轮数，since 起、日期升序。 */
    List<DailyLatencyStat> turnLatencyByDay(Instant since);

    /** 工具时延按工具百分位 + 调用数，工具名升序。 */
    List<ToolLatencyStat> toolLatencyByTool(Instant since);

    /** 工具调用明细行（args 为 JSONB 原文串，前端自行预览截断）。 */
    record ToolTraceRow(
            long id,
            Long userId,
            String conversationId,
            String messageId,
            String toolName,
            String argsJson,
            String resultText,
            int specCount,
            String asOf,
            String asOfKind,
            boolean mcp,
            boolean failed,
            Long durationMs,
            Instant calledAt) {}

    /** 分页结果（total 为筛选后总数，不含分页截断）。 */
    record TracePage(List<ToolTraceRow> rows, long total) {}

    /** 按日消耗桶（date 为上海时区 YYYY-MM-DD；token 列空值按 0 汇总）。 */
    record DailyTokenUsage(String date, long promptTokens, long completionTokens,
                           long totalTokens, long turns) {}

    /** 按工具调用统计（avgDurationMs 为 null = 该工具全无时长记录）。 */
    record ToolCallStat(String tool, long calls, Double avgDurationMs) {}

    /** 轮时延百分位（p50Ms/p95Ms 为 null = 窗口内无带时长的轮）。 */
    record TurnLatencyStat(Double p50Ms, Double p95Ms) {}

    /** 按日轮时延百分位与轮数。 */
    record DailyLatencyStat(String date, Double p50Ms, Double p95Ms, long turns) {}

    /** 按工具时延百分位与调用数。 */
    record ToolLatencyStat(String tool, Double p50Ms, Double p95Ms, long calls) {}
}
