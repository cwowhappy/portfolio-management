package com.portfolio.invest.domain.observability;

import java.time.Instant;
import java.util.Map;

/**
 * 对话轮观测记录（MS-30 §5.2/§5.3）：一轮对话的时延/token/工具数/失败与 trust 信号摘要，
 * 由观测 hook（Task 9）在 PostCall 组装、经 {@link ObservabilityRecorder#recordTurn} 随该轮
 * 工具调用观测批量落 turn_observation（V5）。
 *
 * <p>token 三分量与 durationMs 允许 null（模型 usage 缺失 / PreCall 起点不在场——观测旁路
 * best-effort，缺失行仍计数不进均值插值，见读端口 {@link ObservabilityQueryRepository} 口径约定）；
 * trustStats 为 trust 回报 stats 字段子集（anchor/verified/sourced/corrections），null = 该轮无
 * trust 信号。user_id 弱引用（无 FK），用户删除随保留期自然过期。
 */
public record TurnObservation(
        Long userId,
        String conversationId,
        String messageId,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Long durationMs,
        int toolCount,
        boolean failed,
        String errorSummary,
        Map<String, Object> trustStats,
        Instant createdAt) {}
