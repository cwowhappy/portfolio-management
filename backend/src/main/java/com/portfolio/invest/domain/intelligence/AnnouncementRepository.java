package com.portfolio.invest.domain.intelligence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 公告域仓库端口（intelligence_announcement / intelligence_announcement_extract 双表）。
 *
 * <p>announcement 表由 collector 采集写入（跨服务契约，V3 注释口径）；extract 表由本仓库
 * upsertExtract 独占写入。事务边界在 application 层（照既有仓库先例）。
 */
public interface AnnouncementRepository {

    /**
     * 分页检索：keyword 走标题 trgm 近似（子串 LIKE 保底）+ stockCode 直列等值 +
     * type 走 extract 侧 ann_types JSONB 包含（未抽取条目无标签不命中）+ from/to 闭区间
     * （Asia/Shanghai 折算）+ major（采集侧栏目映射预判）过滤，published_at 倒序。
     * total 与条目分两次查询（OFFSET/LIMIT 分页）。
     */
    PageResult<AnnouncementRecord> search(PageQuery q);

    /**
     * 游标窗口内（Asia/Shanghai 自然日 {@code day.minusDays(lookbackDays-1)} 起至 day 含当日，
     * lookbackDays ≥ 1）入库、且尚无 SUCCESS/FAILED 终态抽取的公告：无抽取行或抽取行为
     * PENDING（LEFT JOIN 反连接）。id 升序稳定取前 limit 条，供抽取批消费——FAILED 为终态
     * 不重试；窗口有界（调用方传 3）保证 LLM 失效/护栏停批后的积压可跨日续抽，又不无限
     * 重试陈年条目。<b>与 {@link NewsRepository#findPendingForExtraction} 同款 3 日窗语义；
     * major/业绩类与否不在仓库层筛选</b>——抽取服务按标题/栏目预筛（LLM prompt 组装层）。
     */
    List<AnnouncementRecord> findPendingForExtraction(LocalDate day, int lookbackDays, int limit);

    /**
     * 写入/覆盖抽取结果（INSERT … ON CONFLICT (announcement_id) DO UPDATE）：无论旧状态
     * 一律以最新结果整体置换（metrics + annTypes + pdfText + status + model + extracted_at）；
     * announcement 无既有抽取行时即插入（首次 upsert 建行）。metrics 为 null（FAILED）时
     * 落 NULL 列。
     */
    void upsertExtract(Long announcementId, AnnouncementExtractResult result);

    /**
     * since 起**完成抽取**（extracted_at 时间轴、含边界，非 published_at——晚间批抽取的当日
     * 盘后公告与跨日续抽的积压条目都命中）、抽取 SUCCESS 且采集侧预判 major=true 的公告，
     * extracted_at 倒序。供公告定向推送（Task 7）批末消费；已推送与否由 push_log 查重，
     * 仓库不管推送幂等。
     */
    List<AnnouncementRecord> findExtractedMajorSince(Instant since);

    /**
     * 是否已有抽取行（含 PENDING/SUCCESS/FAILED 任一态）——消费方区分「尚无抽取行」用；
     * 推送去重不在此（已推过不重推由 push_log 判）。
     */
    boolean existsExtract(Long announcementId);
}
