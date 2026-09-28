package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.research.RefluxState;
import com.portfolio.invest.domain.research.Review;
import com.portfolio.invest.domain.research.ReviewTier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 复盘行（research_review，V2 建表）：answers/auto_snapshot/overrides 三 JSONB 以 String 直存
 * （照 McpUserConfigJpaEntity.disabledTools 的 SqlTypes.JSON 先例；DB jsonb 保证语义有效性，
 * 读回文本可能键序归一——「创建定格」按语义比较，不按字节串）；trade_ids 为 PostgreSQL
 * bigint[]（{@code SqlTypes.ARRAY}，无既有先例、本表首例）；快照列无任何更新路径由域保证。
 */
@Entity
@Table(name = "research_review")
public class ResearchReviewJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReviewTier tier;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    /** S5 维度作答（MS-24 弹性字段集 JSON 直存，可空直至 PUT）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String answers;

    @Column(columnDefinition = "text")
    private String narrative;

    /** F14 定格快照（创建时写入，此后只读）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String autoSnapshot;

    /** 用户覆盖值（与 auto_snapshot 分开存，展示层并列）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String overrides;

    /** D11 归因圈选（trade 软引用数组；无 FK 不级联）。 */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "trade_ids", columnDefinition = "bigint[]")
    private List<Long> tradeIds;

    @Enumerated(EnumType.STRING)
    @Column(name = "reflux_state", nullable = false, length = 16)
    private RefluxState refluxState;

    @Column(name = "wiki_entry_id")
    private Long wikiEntryId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected ResearchReviewJpaEntity() {}

    public static ResearchReviewJpaEntity fromDomain(Review r) {
        ResearchReviewJpaEntity entity = new ResearchReviewJpaEntity();
        entity.id = r.id();
        entity.projectId = r.projectId();
        entity.tier = r.tier();
        entity.periodStart = r.periodStart();
        entity.periodEnd = r.periodEnd();
        entity.answers = r.answersJson();
        entity.narrative = r.narrative();
        entity.autoSnapshot = r.snapshotJson();
        entity.overrides = r.overridesJson();
        entity.tradeIds = r.tradeIds();
        entity.refluxState = r.refluxState();
        entity.wikiEntryId = r.wikiEntryId();
        entity.createdAt = r.createdAt();
        entity.updatedAt = r.updatedAt();
        entity.version = r.version();
        return entity;
    }

    public Review toDomain() {
        return Review.reconstitute(id, projectId, tier, periodStart, periodEnd,
                answers, narrative, autoSnapshot, overrides,
                tradeIds == null ? List.of() : List.copyOf(tradeIds),
                refluxState, wikiEntryId, version, createdAt, updatedAt);
    }
}
