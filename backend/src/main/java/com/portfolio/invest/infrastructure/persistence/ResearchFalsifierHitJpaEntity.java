package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.research.FalsifierHit;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** 证伪命中留痕行（research_falsifier_hit，append-only，Ruling-18：仅 PREDICATE 命中行落表）。 */
@Entity
@Table(name = "research_falsifier_hit")
public class ResearchFalsifierHitJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "falsifier_id", nullable = false)
    private Long falsifierId;

    @Column(nullable = false, length = 200)
    private String basis;

    /** 评审回填位（P4 research_falsifier_review 软引用；由 FalsifierReviewRepositoryImpl.attachReview 唯一写入，不覆盖）。 */
    @Column(name = "review_id")
    private Long reviewId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ResearchFalsifierHitJpaEntity() {}

    public static ResearchFalsifierHitJpaEntity fromDomain(FalsifierHit hit) {
        ResearchFalsifierHitJpaEntity entity = new ResearchFalsifierHitJpaEntity();
        entity.id = hit.id();
        entity.projectId = hit.projectId();
        entity.falsifierId = hit.falsifierId();
        entity.basis = hit.basis();
        entity.createdAt = hit.createdAt();
        return entity;
    }

    public FalsifierHit toDomain() {
        return new FalsifierHit(id, projectId, falsifierId, basis, createdAt);
    }
}
