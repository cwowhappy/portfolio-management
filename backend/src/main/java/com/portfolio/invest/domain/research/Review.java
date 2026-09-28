package com.portfolio.invest.domain.research;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 复盘聚合（M16-F13~F16）：{@code auto_snapshot} 创建时定格（写入后不再变更——无任何
 * 改快照入口，F14「不复算历史」由结构保证）；answers/overrides 分开存（自动值为草稿、
 * 用户可覆盖）；归因圈选 trade_ids 软引用（D11）；回流状态机 PENDING → REFLOWN（F16）。
 * 不可变，变更操作返回新实例（照 {@link StrategyDoc} 先例）。
 *
 * <p>校验：tier 非空（D7 三档）、periodStart ≤ periodEnd、快照非空白（创建即定格）；
 * trade_ids 去重排序后落库（Review Focus 5，手动修正路径与本聚合共用同一收敛点）。
 */
public final class Review {

    private final Long id;
    private final Long projectId;
    private final ReviewTier tier;
    private final LocalDate periodStart;
    private final LocalDate periodEnd;
    private final String snapshotJson;
    private final String answersJson;
    private final String narrative;
    private final String overridesJson;
    private final List<Long> tradeIds;
    private final RefluxState refluxState;
    private final Long wikiEntryId;
    private final Long version;
    private final Instant createdAt;
    private final Instant updatedAt;

    private Review(Long id, Long projectId, ReviewTier tier, LocalDate periodStart, LocalDate periodEnd,
                   String snapshotJson, String answersJson, String narrative, String overridesJson,
                   List<Long> tradeIds, RefluxState refluxState, Long wikiEntryId,
                   Long version, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.projectId = projectId;
        this.tier = tier;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.snapshotJson = snapshotJson;
        this.answersJson = answersJson;
        this.narrative = narrative;
        this.overridesJson = overridesJson;
        this.tradeIds = tradeIds;
        this.refluxState = refluxState;
        this.wikiEntryId = wikiEntryId;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** 新建复盘：快照定格 + trade_ids 去重排序；answers/overrides 空、回流 PENDING。 */
    public static Review create(Long projectId, ReviewTier tier, LocalDate periodStart, LocalDate periodEnd,
                                String snapshotJson, List<Long> tradeIds) {
        if (projectId == null) {
            throw new ResearchException(ResearchErrorCode.PROJECT_REQUIRED, "归属项目不能为空");
        }
        if (tier == null) {
            throw new ResearchException(ResearchErrorCode.TIER_REQUIRED, "复盘档位不能为空");
        }
        if (periodStart == null || periodEnd == null || periodStart.isAfter(periodEnd)) {
            throw new ResearchException(ResearchErrorCode.REVIEW_PERIOD_INVALID,
                    "复盘区间起止不能为空且起点不能晚于终点");
        }
        if (snapshotJson == null || snapshotJson.isBlank()) {
            throw new ResearchException(ResearchErrorCode.SNAPSHOT_REQUIRED, "复盘快照不能为空");
        }
        Instant now = Instant.now();
        return new Review(null, projectId, tier, periodStart, periodEnd, snapshotJson,
                null, null, null, normalize(tradeIds), RefluxState.PENDING, null, null, now, now);
    }

    /** 持久化还原（JPA 转换器用，不做校验）。 */
    public static Review reconstitute(Long id, Long projectId, ReviewTier tier,
                                      LocalDate periodStart, LocalDate periodEnd,
                                      String answersJson, String narrative, String snapshotJson,
                                      String overridesJson, List<Long> tradeIds,
                                      RefluxState refluxState, Long wikiEntryId,
                                      Long version, Instant createdAt, Instant updatedAt) {
        return new Review(id, projectId, tier, periodStart, periodEnd, snapshotJson, answersJson,
                narrative, overridesJson, tradeIds == null ? List.of() : List.copyOf(tradeIds),
                refluxState, wikiEntryId, version, createdAt, updatedAt);
    }

    /** 作答与覆盖整组落（MS-24 弹性字段集 JSON 直存）：变更返新，快照与圈选不动。 */
    public Review applyAnswers(String answersJson, String overridesJson) {
        if (answersJson == null || answersJson.isBlank()) {
            throw new ResearchException(ResearchErrorCode.ANSWERS_REQUIRED, "复盘作答不能为空");
        }
        return new Review(id, projectId, tier, periodStart, periodEnd, snapshotJson,
                answersJson, narrative, overridesJson, tradeIds, refluxState, wikiEntryId,
                version, createdAt, Instant.now());
    }

    /**
     * 确认回流（F16 用户确认后入库）：置 REFLOWN 并记 wiki 条目；REFLOWN 态幂等返回
     * 原实例——既有 entryId 优先，重复确认（即使携带不同条目 id）不重复建条目（Review Focus 4）。
     */
    public Review refluxConfirm(Long wikiEntryId) {
        if (refluxState == RefluxState.REFLOWN) {
            return this;
        }
        if (wikiEntryId == null) {
            throw new ResearchException(ResearchErrorCode.WIKI_ENTRY_REQUIRED, "回流目标条目不能为空");
        }
        return new Review(id, projectId, tier, periodStart, periodEnd, snapshotJson,
                answersJson, narrative, overridesJson, tradeIds, RefluxState.REFLOWN, wikiEntryId,
                version, createdAt, Instant.now());
    }

    /** trade_ids 收敛：null 容忍为空、去重升序（Review Focus 5）。 */
    private static List<Long> normalize(List<Long> tradeIds) {
        if (tradeIds == null || tradeIds.isEmpty()) {
            return List.of();
        }
        return List.copyOf(new ArrayList<>(new TreeSet<>(tradeIds)));
    }

    public Long id() { return id; }
    public Long projectId() { return projectId; }
    public ReviewTier tier() { return tier; }
    public LocalDate periodStart() { return periodStart; }
    public LocalDate periodEnd() { return periodEnd; }
    public String snapshotJson() { return snapshotJson; }
    public String answersJson() { return answersJson; }
    public String narrative() { return narrative; }
    public String overridesJson() { return overridesJson; }
    public List<Long> tradeIds() { return tradeIds; }
    public RefluxState refluxState() { return refluxState; }
    public Long wikiEntryId() { return wikiEntryId; }
    public Long version() { return version; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
