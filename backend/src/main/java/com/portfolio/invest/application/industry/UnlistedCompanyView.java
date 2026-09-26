package com.portfolio.invest.application.industry;

import com.portfolio.invest.domain.industry.UnlistedCompany;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 策展企业行视图（设计规格 §四）：轮次透出枚举名+中文 label 双字段——前端按枚举名映射
 * FundingRound.order() 排序，label 直接渲染（排序不依赖本地枚举副本）。
 */
public record UnlistedCompanyView(Long id, String industryCode, String companyName, String segment,
                                  String latestRound, String latestRoundLabel, LocalDate lastFundingDate,
                                  BigDecimal totalFundingYi, String summary, String sourceNote,
                                  Instant updatedAt) {

    public static UnlistedCompanyView from(UnlistedCompany c) {
        return new UnlistedCompanyView(c.id(), c.industryCode(), c.companyName(), c.segment(),
                c.latestRound().name(), c.latestRound().label(), c.lastFundingDate(),
                c.totalFundingYi(), c.summary(), c.sourceNote(), c.updatedAt());
    }
}
