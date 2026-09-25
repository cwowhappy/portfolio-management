package com.portfolio.invest.application.industry;

import java.time.Instant;

/** 行业关注行视图：行业代码 + 关注时间（Task 10 前端对比入口渲染用）。 */
public record IndustryWatchView(String industryCode, Instant addedAt) {}
