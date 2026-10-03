package com.portfolio.invest.application.intelligence;

import com.portfolio.invest.domain.intelligence.PolicyDirection;
import java.time.LocalDate;

/**
 * P4 政策库页面过滤器（/api/intelligence/policies，D20 §4.3）：q 标题近似 / from·to
 * 闭区间 / direction 取向等值——语义与 {@link com.portfolio.invest.domain.intelligence.PolicyRepository#searchEvents}
 * 对齐（仅 SUCCESS 行进检索面）。分页字段缺省 1/20 由
 * {@link IntelligenceQueryService#policies} 收口后经 PageQuery 夹紧。
 *
 * @param q         关键词（标题近似）
 * @param from      起始日期（含）
 * @param to        结束日期（含）
 * @param direction 政策取向（EASING/TIGHTENING/NEUTRAL；null 不过滤）
 * @param page      页码（null → 1，≥1 夹紧）
 * @param pageSize  页大小（null → 20，夹紧 1..100）
 */
public record PolicyFilter(
        String q,
        LocalDate from,
        LocalDate to,
        PolicyDirection direction,
        Integer page,
        Integer pageSize) {
}
