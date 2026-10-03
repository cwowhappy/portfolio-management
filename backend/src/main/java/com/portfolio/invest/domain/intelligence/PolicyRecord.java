package com.portfolio.invest.domain.intelligence;

import java.time.Instant;

/**
 * 政策 raw 行读取模型：intelligence_policy_raw 左连 intelligence_policy_event 的抽取侧状态。
 *
 * <p>供抽取批（{@link PolicyRepository#findPendingForExtraction}）消费：正文
 * {@code content_text} 为 collector 两跳抓取的全文（T2/T3 已落，截 8000 字防爆内存），
 * 抽取服务截前 6000 字符送 LLM（提示词层职责）。{@code status} 为抽取侧合并状态：
 * null=尚无抽取行、PENDING=占位待抽取（终态 SUCCESS/FAILED 不出现在待抽取结果中）。
 *
 * @param id          raw 主键
 * @param source      采集源标识（pboc/csrc/mof/stats）
 * @param externalId  源站业务键（与 source 联合唯一）
 * @param title       标题（关键词检索列——LIKE+trgm 算子，无 trgm GIN 索引）
 * @param url         原文链接（事件可回溯，验收「政策事件带来源链接」）
 * @param publishedAt 发布时间（findPendingForExtraction 的游标窗口判定列——raw 表无 fetched_at）
 * @param contentText 政策正文全文（可能为 null：详情页抓取失败时 collector 落空）
 * @param status      抽取状态（null=尚无抽取行）
 */
public record PolicyRecord(
        Long id,
        String source,
        String externalId,
        String title,
        String url,
        Instant publishedAt,
        String contentText,
        ExtractStatus status) {
}
