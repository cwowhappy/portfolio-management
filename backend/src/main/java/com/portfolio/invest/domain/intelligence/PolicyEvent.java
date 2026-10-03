package com.portfolio.invest.domain.intelligence;

import java.time.Instant;
import java.util.List;

/**
 * 政策事件读取模型：intelligence_policy_event 连 intelligence_policy_raw 的合并视图
 * （{@link PolicyRepository#searchEvents} 出参，P4 政策库页面与 T6 简报「宏观与政策」节消费）。
 *
 * <p><b>isPolicy 派生</b>：表无 is_policy 列——抽取侧对 LLM 判定 isPolicy=false 的漏网条目
 * （领导活动/会议新闻/转载/行政事务）以 {@link #NON_POLICY_SUMMARY} 哨兵 summary 落库
 * （F12「误收录须标注为低置信」——落库而非丢弃），读取侧 {@link #of} 据哨兵派生
 * {@code isPolicy=false}，消费方据此过滤或标注展示。
 *
 * @param id           事件行主键
 * @param policyRawId  raw 行外键（UNIQUE，一对一）
 * @param source       采集源标识（pboc/csrc/mof/stats）
 * @param externalId   源站业务键
 * @param title        政策标题（关键词检索列——LIKE+trgm 算子，无 trgm GIN 索引）
 * @param url          原文链接（验收「政策事件带来源链接——每条 url 可回溯」）
 * @param publishedAt  发布时间
 * @param direction    政策取向
 * @param strength     政策力度
 * @param affectedAreas 影响领域（JSONB，null 归一为空列表）
 * @param summary      一句话摘要（哨兵文案 = 非政策兜底行）
 * @param confidence   置信度（LOW=低置信标注）
 * @param isPolicy     是否政策类发布（哨兵 summary 派生，见表结构注释）
 * @param status       抽取状态
 * @param model        抽取模型标识
 * @param extractedAt  抽取落库时间
 */
public record PolicyEvent(
        Long id,
        Long policyRawId,
        String source,
        String externalId,
        String title,
        String url,
        Instant publishedAt,
        PolicyDirection direction,
        PolicyStrength strength,
        List<String> affectedAreas,
        String summary,
        PolicyConfidence confidence,
        boolean isPolicy,
        ExtractStatus status,
        String model,
        Instant extractedAt) {

    /** 非政策类兜底行哨兵 summary（写入侧 PolicyExtractor 与读取侧 {@link #of} 共用）。 */
    public static final String NON_POLICY_SUMMARY = "非政策类动态（过滤兜底）";

    public PolicyEvent {
        affectedAreas = affectedAreas == null ? List.of() : List.copyOf(affectedAreas);
    }

    /** 合并视图工厂：isPolicy 由哨兵 summary 派生（表无 is_policy 列）。 */
    public static PolicyEvent of(Long id, Long policyRawId, String source, String externalId,
                                 String title, String url, Instant publishedAt,
                                 PolicyDirection direction, PolicyStrength strength,
                                 List<String> affectedAreas, String summary,
                                 PolicyConfidence confidence, ExtractStatus status,
                                 String model, Instant extractedAt) {
        return new PolicyEvent(id, policyRawId, source, externalId, title, url, publishedAt,
                direction, strength, affectedAreas, summary, confidence,
                !NON_POLICY_SUMMARY.equals(summary), status, model, extractedAt);
    }
}
