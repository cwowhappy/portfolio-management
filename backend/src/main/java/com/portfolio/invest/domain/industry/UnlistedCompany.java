package com.portfolio.invest.domain.industry;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 未上市策展企业聚合（MS-10 决策 #1）：全局公共研究数据，无 user 归属。
 * id 由库生成（插入时为 null）；幂等键 = industryCode + companyName。
 */
public record UnlistedCompany(Long id, String industryCode, String companyName, String segment,
                              FundingRound latestRound, LocalDate lastFundingDate,
                              BigDecimal totalFundingYi, String summary, String sourceNote,
                              Instant updatedAt) {
}
