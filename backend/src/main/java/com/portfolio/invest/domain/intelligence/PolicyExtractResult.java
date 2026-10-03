package com.portfolio.invest.domain.intelligence;

import java.time.Instant;
import java.util.List;

/**
 * 单条政策的 LLM 结构化抽取结果（{@link PolicyRepository#upsertExtract} 入参）。
 *
 * <p>status 由调用方（PolicyExtractionService）判定：JSON 解析与字段校验通过为 SUCCESS、
 * 否则 FAILED——FAILED 时分析字段可空，仅状态与留痕字段有意义。affectedAreas null 归一为
 * 空列表（落库为 '[]'::jsonb）。extractedAt 为空时由仓库侧落 now()。
 *
 * <p><b>isPolicy 的存储编码</b>：表无 is_policy 列——非政策类兜底行由调用方以
 * {@link PolicyEvent#NON_POLICY_SUMMARY} 哨兵 summary 落库（direction=NEUTRAL、
 * strength=LOW、confidence=LOW），读取侧 {@link PolicyEvent#of} 据哨兵派生 isPolicy。
 *
 * @param direction     政策取向（无法判断为 null）
 * @param strength      政策力度（无法判断为 null）
 * @param affectedAreas 影响领域（中文短语数组）
 * @param summary       一句话摘要
 * @param confidence    置信度（LOW=低置信标注，F12）
 * @param status        SUCCESS / FAILED
 * @param model         模型标识
 * @param extractedAt   抽取时间
 */
public record PolicyExtractResult(
        PolicyDirection direction,
        PolicyStrength strength,
        List<String> affectedAreas,
        String summary,
        PolicyConfidence confidence,
        ExtractStatus status,
        String model,
        Instant extractedAt) {

    public PolicyExtractResult {
        affectedAreas = affectedAreas == null ? List.of() : List.copyOf(affectedAreas);
    }

    /** SUCCESS 结果便捷工厂（分析字段齐全）。 */
    public static PolicyExtractResult success(PolicyDirection direction, PolicyStrength strength,
                                              List<String> affectedAreas, String summary,
                                              PolicyConfidence confidence, String model,
                                              Instant extractedAt) {
        return new PolicyExtractResult(direction, strength, affectedAreas, summary, confidence,
                ExtractStatus.SUCCESS, model, extractedAt);
    }
}
