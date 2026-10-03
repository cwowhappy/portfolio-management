package com.portfolio.invest.application.intelligence;

import java.util.Optional;

/**
 * 情报域 LLM 通道端口（infrastructure/intelligence 实现）：新闻抽取/简报生成/政策解析等
 * 情报定时任务的 DeepSeek 单轮补全统一入口。LLM 未配置或调用失败一律返回 empty，
 * 调用方静默跳批——定时链路绝不因 LLM 故障中断。
 */
public interface IntelligenceChatPort {

    /** 单轮补全；返回文本与 input token 数；LLM 未配置返回 empty（调用方静默跳批）。 */
    Optional<ChatOutcome> complete(String systemPrompt, String userPrompt);

    /**
     * 补全结果。
     *
     * @param text        全部 chunk 的 TextBlock 按序拼接文本
     * @param inputTokens 各 chunk 非 null usage 的 inputTokens 累加
     */
    record ChatOutcome(String text, long inputTokens) {}
}
