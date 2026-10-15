package com.portfolio.invest.domain.market;

/**
 * 已规范化的新闻条目中间结构（供应商无关）：由基础设施层把上游 wire format
 * （如东财 result.cmsArticleWebOld 的 title/content/mediaName/date/url 字段）映射为本
 * 结构后交给 {@link MarketDataParser}——HTML 剥离与摘要截断等文本归一化由 domain 解析器
 * 承担，字段码只存在 infrastructure（2026-10-15 审查 §五 L2 字段码下沉配套引入）。
 *
 * @param title   标题（原始文本，可含 HTML 标签）
 * @param content 正文（原始文本，可含 HTML 实体）
 * @param media   媒体名
 * @param date    发布日期时间
 * @param url     原文链接
 */
public record RawNewsItem(
        String title,
        String content,
        String media,
        String date,
        String url) {
}
