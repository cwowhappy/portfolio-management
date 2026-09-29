package com.portfolio.invest.domain.intelligence;

import java.time.LocalDate;

/**
 * 情报域分页查询载体（D20 分页协议的 domain 侧）：页码/页大小 + 新闻/公告两侧过滤器。
 *
 * <p>必须落在 domain：仓库接口 {@link NewsRepository#search} /
 * {@link AnnouncementRepository#search} 消费它，而 application 不得被 domain 反向依赖；
 * application/IntelligenceViews 的 {@code PageView<T>} 是由 {@link PageResult} 映射出的
 * API 信封（另一 record）。
 *
 * <p>夹紧规则（D20）：page 下限 1、pageSize 夹紧 1..100——compact constructor 内统一处理，
 * 调用方（web 参数绑定 / agent 工具）无需各自防御。过滤器全部可空，null 即不启用该条件。
 * from/to 为闭区间（yyyy-MM-dd，按 Asia/Shanghai 折算为 published_at 时间窗）。
 * 新闻侧仓库消费 keyword/stockCode/industryCode/minImportance，公告侧仓库消费
 * keyword/stockCode/type/major——统一载体，各仓库忽略不适用于自己的过滤器（设计规格 §3）。
 */
public record PageQuery(
        int page,
        int pageSize,
        String keyword,
        String stockCode,
        String industryCode,
        LocalDate from,
        LocalDate to,
        Integer minImportance,
        AnnouncementType type,
        Boolean major) {

    /** pageSize 上限（D20：默认 20、夹紧 1..100，默认值由 web 层参数缺省提供）。 */
    public static final int MAX_PAGE_SIZE = 100;

    public PageQuery {
        page = Math.max(page, 1);
        pageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
    }

    /** 新闻侧八参构造（公告侧 type/major 缺省 null 不启用；既有调用点兼容）。 */
    public PageQuery(int page, int pageSize, String keyword, String stockCode,
                     String industryCode, LocalDate from, LocalDate to, Integer minImportance) {
        this(page, pageSize, keyword, stockCode, industryCode, from, to, minImportance, null, null);
    }

    /** 无过滤器分页（全量时间倒序流）。 */
    public static PageQuery of(int page, int pageSize) {
        return new PageQuery(page, pageSize, null, null, null, null, null, null, null, null);
    }
}
