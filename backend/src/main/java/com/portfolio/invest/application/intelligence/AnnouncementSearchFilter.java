package com.portfolio.invest.application.intelligence;

import com.portfolio.invest.domain.intelligence.AnnouncementType;
import java.time.LocalDate;

/**
 * 公告检索过滤器（agent 工具与 P4 web 查询共用入参载体）：全部可空，null 即不启用该条件。
 *
 * <p>{@code q} 按标题近似匹配（trgm）；{@code stock} 标的代码直列等值；{@code type} 走
 * extract 侧 ann_types JSONB 包含（未抽取条目无标签不命中）；{@code from}/{@code to} 为
 * 发布日闭区间（yyyy-MM-dd，Asia/Shanghai 折算）；{@code scope} 检索范围（缺省
 * {@link AnnouncementScope#ALL}）；{@code limit} 由
 * {@link IntelligenceQueryService#searchAnnouncements} 统一夹紧 1..20（缺省 10）。
 *
 * @param q     关键词（标题近似）
 * @param stock 标的代码，如 600519
 * @param type  公告类型（十类 + OTHER）
 * @param from  起始日期（含）
 * @param to    结束日期（含）
 * @param scope 检索范围（null 视为 ALL）
 * @param limit 返回条数（服务内夹紧 1..20，缺省 10）
 */
public record AnnouncementSearchFilter(
        String q,
        String stock,
        AnnouncementType type,
        LocalDate from,
        LocalDate to,
        AnnouncementScope scope,
        Integer limit) {
}
