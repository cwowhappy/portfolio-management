package com.portfolio.invest.application.intelligence;

import java.time.LocalDate;

/**
 * 情报检索过滤器（agent 工具与 P4 web 查询共用入参载体）：全部可空，null 即不启用该条件。
 *
 * <p>{@code q} 按标题近似匹配（trgm）；{@code stock}/{@code industry} 为抽取侧标的/行业码
 * （JSONB 包含）；{@code from}/{@code to} 为发布日闭区间（yyyy-MM-dd，Asia/Shanghai 折算）；
 * {@code minImportance} 为重要度下限 0..100；{@code limit} 由
 * {@link IntelligenceQueryService#searchNews} 统一夹紧 1..20（缺省 10）。
 *
 * @param q             关键词（标题近似）
 * @param stock         标的代码，如 600519
 * @param industry      申万行业码，如 801140
 * @param from          起始日期（含）
 * @param to            结束日期（含）
 * @param minImportance 重要度下限 0..100
 * @param limit         返回条数（服务内夹紧 1..20，缺省 10）
 */
public record NewsSearchFilter(
        String q,
        String stock,
        String industry,
        LocalDate from,
        LocalDate to,
        Integer minImportance,
        Integer limit) {
}
