package com.portfolio.invest.domain.intelligence;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 宏观日历条目（intelligence_macro_calendar 行，F14 决策 #29）：指标的**预期**发布
 * 日程——预期非承诺（实际发布可能提前/推迟/缺席，展示层须带此口径）。
 *
 * <p>该表由 V3 迁移 seed（五指标 × 2026Q4~2027 共 75 行）+ 之后每年 SQL 人工续写
 * 维护，backend 全程只读；{@code updated_at} 随人工维护刷新。
 *
 * @param indicator    指标码（CPI/PPI/PMI/LPR/AFMI）
 * @param expectedDate 预期发布日（复合主键之一；PMI 为月末、LPR 每月 20 日等）
 * @param frequency    发布频率（MONTH）
 * @param sourceSite   来源站点名（国家统计局 / 中国人民银行）
 * @param updatedAt    日历行最近维护时间（非指标发布时间）
 */
public record MacroCalendarEntry(
        String indicator,
        LocalDate expectedDate,
        String frequency,
        String sourceSite,
        Instant updatedAt) {
}
