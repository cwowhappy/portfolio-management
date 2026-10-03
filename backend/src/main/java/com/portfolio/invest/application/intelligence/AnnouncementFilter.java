package com.portfolio.invest.application.intelligence;

import com.portfolio.invest.domain.intelligence.AnnouncementType;
import java.time.LocalDate;

/**
 * P4 公告流页面过滤器（/api/intelligence/announcements，D20 §4.3）：与工具侧
 * {@link AnnouncementSearchFilter} 共用仓库过滤语义但<b>无 scope 概念</b>（scope 为
 * Agent 工具的定向检索入参，web 页面走全库 + 显式过滤），多 major 维度（采集侧
 * 栏目映射预判）。分页字段缺省 1/20 由 {@link IntelligenceQueryService#announcementsPage}
 * 收口后经 PageQuery 夹紧。
 *
 * @param q        关键词（标题近似）
 * @param stock    标的代码（直列等值），如 600519
 * @param type     公告类型（extract 侧 ann_types JSONB 包含）
 * @param from     起始日期（含）
 * @param to       结束日期（含）
 * @param major    仅重大公告（采集侧预判）
 * @param page     页码（null → 1，≥1 夹紧）
 * @param pageSize 页大小（null → 20，夹紧 1..100）
 */
public record AnnouncementFilter(
        String q,
        String stock,
        AnnouncementType type,
        LocalDate from,
        LocalDate to,
        Boolean major,
        Integer page,
        Integer pageSize) {
}
