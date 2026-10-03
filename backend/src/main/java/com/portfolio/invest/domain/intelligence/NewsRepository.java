package com.portfolio.invest.domain.intelligence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 新闻域仓库端口（intelligence_news_raw / intelligence_news_extract 双表）。
 *
 * <p>raw 表由 collector 采集写入（跨服务契约，V3 注释口径）；extract 表由本仓库
 * upsertExtract 独占写入。事务边界在 application 层（照既有仓库先例）。
 */
public interface NewsRepository {

    /**
     * 分页检索：keyword 走标题 trgm 近似（子串 LIKE 保底）+ stockCode/industryCode
     * JSONB 包含 + from/to 闭区间（Asia/Shanghai 折算）+ minImportance 下限，
     * published_at 倒序。total 与条目分两次查询（OFFSET/LIMIT 分页）。
     */
    PageResult<NewsRecord> search(PageQuery q);

    /**
     * 游标窗口内（Asia/Shanghai 自然日 {@code day.minusDays(lookbackDays-1)} 起至 day 含当日，
     * lookbackDays ≥ 1）入库、且尚无 SUCCESS/FAILED 终态抽取的 raw：无抽取行或抽取行为
     * PENDING（LEFT JOIN 反连接）。id 升序稳定取前 limit 条，供抽取批（Task 8）消费——
     * FAILED 为终态不重试；窗口有界（调用方传 3）保证 LLM 失效/护栏停批后的积压可跨日
     * 续抽，又不无限重试陈年条目。
     */
    List<NewsRecord> findPendingForExtraction(LocalDate day, int lookbackDays, int limit);

    /**
     * 同 {@link #findPendingForExtraction} 窗口与 PENDING 口径的**条数**（无 limit 截断）：
     * 盘前简报归档日志消费（抽取积压可观测——limit 截断的取数查不出真实剩余量）。
     */
    long countPendingInWindow(LocalDate day, int lookbackDays);

    /**
     * 写入/覆盖抽取结果（INSERT … ON CONFLICT (news_raw_id) DO UPDATE）：无论旧状态
     * 一律以最新结果整体置换（分析字段 + status + model + extracted_at）；
     * raw 无既有抽取行时即插入（首次 upsert 建行）。
     */
    void upsertExtract(Long newsRawId, NewsExtractResult result);

    /**
     * 删除 published_at 早于 cutoff 的 raw（返回删除行数），extract 经 FK ON DELETE CASCADE
     * 级联清除——90 天滚动清理（Task 11）消费。以 published_at（业务时间轴）为准，
     * 与 idx_intelligence_news_raw_published 索引对齐。
     */
    long deleteRawBefore(Instant cutoff);

    /** 当日（Asia/Shanghai）完成抽取（extracted_at 落在自然日内，含 SUCCESS/FAILED）的行数。 */
    long countExtractedByDate(LocalDate day);

    /**
     * since 起发布、抽取 SUCCESS 且 importance ≥ majorAt 的新闻（简报选取，Task 9 消费）。
     * majorAt 阈值由调用方传入（ImportanceGrade 分档属应用配置，仓库不读配置）。
     */
    List<NewsRecord> findMajorSince(Instant since, int majorAt);
}
