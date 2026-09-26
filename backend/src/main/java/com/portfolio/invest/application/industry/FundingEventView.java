package com.portfolio.invest.application.industry;

import com.portfolio.invest.domain.industry.FundingEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 融资事件行视图（设计规格 §四读侧）：与 {@link UnlistedCompanyView} 同口径透出轮次
 * 双字段——枚举名 + label()，前端排序与渲染两用。
 */
public record FundingEventView(Long id, LocalDate eventDate, String companyName, String round,
                               String roundLabel, BigDecimal amountYi, String investors,
                               String industryCode, String segment, String sourceTitle,
                               String sourceUrl, Instant createdAt) {

    public static FundingEventView from(FundingEvent e) {
        return new FundingEventView(e.id(), e.eventDate(), e.companyName(), e.round().name(),
                e.round().label(), e.amountYi(), e.investors(), e.industryCode(), e.segment(),
                e.sourceTitle(), e.sourceUrl(), e.createdAt());
    }
}
