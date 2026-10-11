package com.portfolio.invest.domain.research;

import java.time.LocalDate;

/**
 * 用户日 token 配额读模型（MS-32，设计规格 §3.9 / §二 report_token_quota 行）：
 * (userId, quotaDate) 定位的当日累计读数；写入走仓库 settle upsert（B3），纯读无行为。
 */
public record ReportTokenQuota(Long userId, LocalDate quotaDate, long tokensUsed) {}
