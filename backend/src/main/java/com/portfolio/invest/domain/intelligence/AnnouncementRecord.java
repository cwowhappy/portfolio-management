package com.portfolio.invest.domain.intelligence;

import java.time.Instant;
import java.util.List;

/**
 * 公告读取模型：intelligence_announcement 左连 intelligence_announcement_extract 的合并视图。
 *
 * <p>前半（公告九列 + 主键/入库时间）恒有值；后半（metrics 起）为 AI 抽取侧字段，尚无
 * 抽取行时为 null（LEFT JOIN 无行），有 PENDING 占位行时 status=PENDING、分析字段空。
 * search / findPendingForExtraction / findExtractedMajorSince 共用本视图。
 *
 * @param id            announcement 主键
 * @param source        采集源标识（cninfo/eastmoney…）
 * @param externalId    源站业务键（与 source 联合唯一）
 * @param stockCode     标的代码（公告表直列，检索过滤列）
 * @param stockName     标的名称
 * @param title         标题（trgm 检索列）
 * @param annTypeSource 源站栏目（决策 #24 栏目映射的预判输入）
 * @param major         栏目映射预判的重大类型（采集侧，§2.3-6）
 * @param publishedAt   发布时间
 * @param pdfUrl        PDF 原文链接
 * @param fetchedAt     入库时间（findPendingForExtraction 的游标窗口判定列）
 * @param metrics       六字段业绩要点（未抽取成功为 null；未披露字段 null 且进 undisclosed）
 * @param annTypes      公告类型标签（栏目预判 ∪ LLM 精判；null 归一为空列表）
 * @param pdfText       PDF 抽取文本
 * @param status        抽取状态（null=尚无抽取行）
 * @param model         抽取模型标识
 * @param extractedAt   抽取落库时间
 */
public record AnnouncementRecord(
        Long id,
        String source,
        String externalId,
        String stockCode,
        String stockName,
        String title,
        String annTypeSource,
        boolean major,
        Instant publishedAt,
        String pdfUrl,
        Instant fetchedAt,
        AnnouncementMetrics metrics,
        List<AnnouncementType> annTypes,
        String pdfText,
        ExtractStatus status,
        String model,
        Instant extractedAt) {

    public AnnouncementRecord {
        annTypes = annTypes == null ? List.of() : List.copyOf(annTypes);
    }
}
