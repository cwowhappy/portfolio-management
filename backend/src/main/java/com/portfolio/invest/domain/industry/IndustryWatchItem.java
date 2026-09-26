package com.portfolio.invest.domain.industry;

import java.time.Instant;

/** 行业关注项：登录用户关注的行业（申万行业代码，与持仓/自选互不依赖）。 */
public record IndustryWatchItem(Long id, Long userId, String industryCode, Instant addedAt) {
}
