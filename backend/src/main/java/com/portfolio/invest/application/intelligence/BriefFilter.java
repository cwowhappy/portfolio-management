package com.portfolio.invest.application.intelligence;

import java.time.LocalDate;

/**
 * P4 简报归档页面过滤器（/api/intelligence/briefs，D20 §4.3 · 决策 #23 检索维度）：
 * stock 命中 top_stocks JSONB contains（该标的出现在当日简报快照）、q 走 content_md
 * trgm 近似、from/to 为 trade_date 闭区间（简报按交易日归档，日期直比而非时刻折算）。
 * 分页字段缺省 1/20 由 {@link IntelligenceQueryService#briefs} 收口后经 PageQuery 夹紧。
 *
 * @param q        关键词（content_md 近似）
 * @param from     起始交易日（含）
 * @param to       结束交易日（含）
 * @param stock    标的代码（top_stocks 包含），如 600519
 * @param page     页码（null → 1，≥1 夹紧）
 * @param pageSize 页大小（null → 20，夹紧 1..100）
 */
public record BriefFilter(
        String q,
        LocalDate from,
        LocalDate to,
        String stock,
        Integer page,
        Integer pageSize) {
}
