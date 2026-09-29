package com.portfolio.invest.domain.intelligence;

import java.time.Instant;
import java.util.List;

/**
 * 单条公告的 LLM 结构化抽取结果（{@link AnnouncementRepository#upsertExtract} 入参）。
 *
 * <p>status 由调用方（AnnouncementExtractionService）判定：JSON 解析与字段校验通过为
 * SUCCESS、否则 FAILED——FAILED 时分析字段可空，仅状态与留痕字段有意义。annTypes 为
 * 栏目预判（采集侧 ann_type_source 映射）∪ LLM 精判的并集（调用方合成后传入）。列表字段
 * null 归一为空列表（落库为 '[]'::jsonb）；metrics 为 null（FAILED）时落 NULL 列。
 * extractedAt 为空时由仓库侧落 now()。
 *
 * @param metrics     六字段业绩要点（未披露字段 null 且进 undisclosed，严禁编造）
 * @param annTypes    公告类型标签（AnnouncementType 枚举名数组落 JSONB）
 * @param pdfText     PDF 抽取文本（D18 留痕）
 * @param status      SUCCESS / FAILED
 * @param model       模型标识
 * @param extractedAt 抽取时间
 */
public record AnnouncementExtractResult(
        AnnouncementMetrics metrics,
        List<AnnouncementType> annTypes,
        String pdfText,
        ExtractStatus status,
        String model,
        Instant extractedAt) {

    public AnnouncementExtractResult {
        annTypes = annTypes == null ? List.of() : List.copyOf(annTypes);
    }
}
