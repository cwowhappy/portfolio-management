package com.portfolio.invest.domain.intelligence;

import java.time.Instant;
import java.util.List;

/**
 * 单条新闻的 LLM 结构化抽取结果（{@link NewsRepository#upsertExtract} 入参）。
 *
 * <p>status 由调用方（Task 8 NewsExtractionService）判定：JSON 解析与字段校验通过为
 * SUCCESS、否则 FAILED——FAILED 时分析字段可空，仅状态与留痕字段有意义。
 * 列表字段 null 归一为空列表（落库为 '[]'::jsonb）。extractedAt 为空时由仓库侧落 now()。
 *
 * @param eventType     事件类型
 * @param stockCodes    关联标的码
 * @param industryCodes 关联行业码
 * @param summary       AI 摘要
 * @param direction     方向（无法判断为 null）
 * @param keyNumbers    关键数字（自描述字符串数组）
 * @param importance    重要度 0..100（调用方夹紧后传入；FAILED 可 null）
 * @param status        SUCCESS / FAILED
 * @param model         模型标识
 * @param extractedAt   抽取时间
 */
public record NewsExtractResult(
        String eventType,
        List<String> stockCodes,
        List<String> industryCodes,
        String summary,
        Direction direction,
        List<String> keyNumbers,
        Integer importance,
        ExtractStatus status,
        String model,
        Instant extractedAt) {

    public NewsExtractResult {
        stockCodes = stockCodes == null ? List.of() : List.copyOf(stockCodes);
        industryCodes = industryCodes == null ? List.of() : List.copyOf(industryCodes);
        keyNumbers = keyNumbers == null ? List.of() : List.copyOf(keyNumbers);
    }

    /** SUCCESS 结果便捷工厂（分析字段齐全）。 */
    public static NewsExtractResult success(String eventType, List<String> stockCodes,
                                            List<String> industryCodes, String summary,
                                            Direction direction, List<String> keyNumbers,
                                            int importance, String model, Instant extractedAt) {
        return new NewsExtractResult(eventType, stockCodes, industryCodes, summary, direction,
                keyNumbers, importance, ExtractStatus.SUCCESS, model, extractedAt);
    }
}
