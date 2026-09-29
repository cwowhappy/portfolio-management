package com.portfolio.invest.domain.intelligence;

import java.time.Instant;
import java.util.List;

/**
 * 新闻读取模型：intelligence_news_raw 左连 intelligence_news_extract 的合并视图。
 *
 * <p>前半（raw 摘要/标题/发布时间/源站标的标签）恒有值；后半（eventType 起为 AI 抽取侧）
 * 在尚无抽取行时为 null（LEFT JOIN 无行），有 PENDING 占位行时 status=PENDING、分析字段 null。
 * search / findPendingForExtraction / findMajorSince 共用本视图。
 *
 * @param id             raw 主键
 * @param source         采集源标识（eastmoney/sina…）
 * @param externalId     源站业务键（与 source 联合唯一）
 * @param title          标题（trgm 检索列）
 * @param rawSummary     源站摘要（intelligence_news_raw.summary）
 * @param publishedAt    发布时间
 * @param url            原文链接
 * @param stockTags      源站标的标签原样 JSON 文本——域不解释；形状随源站而异：
 *                      eastmoney_724=带市场前缀的字符串数组（如 ["1.600519"]）、
 *                      sina_zhibo=code/name 对象数组（如 [{"code":"600519","name":"贵州茅台"}]），
 *                      消费方（P4 F17 聚合）须按 source 分派
 * @param fetchedAt      入库时间（findPendingForExtraction 的游标窗口判定列）
 * @param eventType      抽取事件类型
 * @param stockCodes     抽取关联标的码（JSONB）
 * @param industryCodes  抽取关联行业码（JSONB）
 * @param extractSummary AI 摘要（intelligence_news_extract.summary）
 * @param direction      方向
 * @param keyNumbers     关键数字（自描述字符串数组，如「Q3 净利润同比 +25.3%」）
 * @param importance     重要度 0..100（null=未抽取成功）
 * @param status         抽取状态（null=尚无抽取行）
 * @param model          抽取模型标识
 * @param extractedAt    抽取落库时间
 */
public record NewsRecord(
        Long id,
        String source,
        String externalId,
        String title,
        String rawSummary,
        Instant publishedAt,
        String url,
        String stockTags,
        Instant fetchedAt,
        String eventType,
        List<String> stockCodes,
        List<String> industryCodes,
        String extractSummary,
        Direction direction,
        List<String> keyNumbers,
        Integer importance,
        ExtractStatus status,
        String model,
        Instant extractedAt) {

    public NewsRecord {
        stockCodes = stockCodes == null ? List.of() : List.copyOf(stockCodes);
        industryCodes = industryCodes == null ? List.of() : List.copyOf(industryCodes);
        keyNumbers = keyNumbers == null ? List.of() : List.copyOf(keyNumbers);
    }
}
