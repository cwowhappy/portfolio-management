package com.portfolio.invest.domain.industry;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 融资事件聚合（MS-10 决策 #3）：公开源人工月度摘录（非全量口径，来源字段留痕）。
 * id 由库生成（插入时为 null）；幂等键 = eventDate + companyName + round（同日同企同轮视为同一事件）。
 */
public record FundingEvent(Long id, LocalDate eventDate, String companyName, FundingRound round,
                           BigDecimal amountYi, String investors, String industryCode, String segment,
                           String sourceTitle, String sourceUrl, Instant createdAt) {
}
