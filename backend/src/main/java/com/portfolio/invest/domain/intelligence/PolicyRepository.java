package com.portfolio.invest.domain.intelligence;

import java.time.LocalDate;
import java.util.List;

/**
 * 政策域仓库端口（intelligence_policy_raw / intelligence_policy_event 双表）。
 *
 * <p>raw 表由 collector 采集写入（跨服务契约，V3 注释口径）；event 表由本仓库
 * upsertExtract 独占写入。<b>长期保留语义</b>：event 对 raw 的 FK 无级联删除，
 * 本仓库不提供任何删除方法（政策不滚动清理，区别于新闻 90 天清理）。
 * 事务边界在 application 层（照既有仓库先例）。
 */
public interface PolicyRepository {

    /**
     * 分页检索政策事件（P4 政策库页面用）：仅 SUCCESS 行（PENDING/FAILED 为抽取中间态不出
     * 检索面；isPolicy=false 兜底行为 SUCCESS、哨兵 summary 落库——低置信标注后可见，F12）。
     * keyword 走标题 trgm 近似（子串 LIKE 保底）+ from/to 闭区间（Asia/Shanghai 折算）+
     * direction 等值过滤，published_at 倒序。total 与条目分两次查询（OFFSET/LIMIT 分页）。
     */
    PageResult<PolicyEvent> searchEvents(PageQuery q);

    /**
     * 游标窗口内（Asia/Shanghai 自然日 {@code day.minusDays(lookbackDays-1)} 起至 day 含当日，
     * lookbackDays ≥ 1）发布、且尚无 SUCCESS/FAILED 终态抽取的政策：无抽取行或抽取行为
     * PENDING（LEFT JOIN 反连接）。id 升序稳定取前 limit 条，供抽取批消费——FAILED 为终态
     * 不重试；窗口有界（调用方传 3）保证 LLM 失效/护栏停批后的积压可跨日续抽，又不无限
     * 重试陈年条目。<b>窗口判定列为 published_at</b>（政策 raw 表无 fetched_at 入库列，
     * 区别于新闻/公告），幂等键 (source, external_id) 保证 collector 重跑不重复计数。
     */
    List<PolicyRecord> findPendingForExtraction(LocalDate day, int lookbackDays, int limit);

    /**
     * 写入/覆盖抽取结果（INSERT … ON CONFLICT (policy_raw_id) DO UPDATE）：无论旧状态
     * 一律以最新结果整体置换（direction + strength + affected_areas + summary +
     * confidence + status + model + extracted_at）；raw 无既有抽取行时即插入
     * （首次 upsert 建行）。
     */
    void upsertExtract(Long policyRawId, PolicyExtractResult result);
}
