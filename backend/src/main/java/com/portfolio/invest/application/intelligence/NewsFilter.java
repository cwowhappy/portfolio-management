package com.portfolio.invest.application.intelligence;

import java.time.LocalDate;

/**
 * P4 新闻流页面过滤器（/api/intelligence/news，D20 §4.3）：与工具侧
 * {@link NewsSearchFilter} 共用仓库过滤语义（q 标题近似 / stock·industry JSONB 包含 /
 * from·to 闭区间 / minImportance 下限），分页字段归本过滤器——page/pageSize 缺省
 * 1/20 由 {@link IntelligenceQueryService#newsPage} 收口后经 PageQuery 夹紧。
 *
 * @param q             关键词（标题近似）
 * @param stock         标的代码，如 600519
 * @param industry      申万行业码，如 801140
 * @param from          起始日期（含）
 * @param to            结束日期（含）
 * @param minImportance 重要度下限 0..100
 * @param page          页码（null → 1，≥1 夹紧）
 * @param pageSize      页大小（null → 20，夹紧 1..100）
 */
public record NewsFilter(
        String q,
        String stock,
        String industry,
        LocalDate from,
        LocalDate to,
        Integer minImportance,
        Integer page,
        Integer pageSize) {
}
