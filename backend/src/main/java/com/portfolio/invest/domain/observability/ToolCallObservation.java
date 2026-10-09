package com.portfolio.invest.domain.observability;

import java.time.Instant;

/**
 * 单次工具调用观测（MS-30 §5.1/§5.3）：装饰器旁路计时与真值摘要，由观测 hook（Task 9）随
 * {@link TurnObservation} 批量落 tool_invocation_obs（V5）。
 *
 * <p><strong>argsJson</strong>为参数的 JSON 字符串（落 JSONB，序列化归调用方——域端口不引
 * Jackson）；<strong>resultText 传原文</strong>，字节安全截断（result-text-max-bytes）由端口
 * 实现统一执行；<strong>asOfKind</strong>用 trust 线名（data/generated/call，域层不依赖
 * agent.trust 枚举——依赖方向 agent → domain 单向）。
 */
public record ToolCallObservation(
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
        long durationMs,
        Instant calledAt) {}
