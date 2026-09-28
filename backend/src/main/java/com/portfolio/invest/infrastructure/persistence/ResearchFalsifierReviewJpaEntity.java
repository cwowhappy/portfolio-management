package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.research.FalsifierReview;
import com.portfolio.invest.domain.research.ReviewConclusion;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** 证伪评审留痕行（research_falsifier_review，append-only：无 version/updated_at、无 update 用例）。 */
@Entity
@Table(name = "research_falsifier_review")
public class ResearchFalsifierReviewJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReviewConclusion conclusion;

    @Column(nullable = false, length = 1000)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ResearchFalsifierReviewJpaEntity() {}

    public static ResearchFalsifierReviewJpaEntity fromDomain(FalsifierReview r) {
        ResearchFalsifierReviewJpaEntity entity = new ResearchFalsifierReviewJpaEntity();
        entity.id = r.id();
        entity.projectId = r.projectId();
        entity.conclusion = r.conclusion();
        entity.reason = r.reason();
        entity.createdAt = r.createdAt();
        return entity;
    }

    public FalsifierReview toDomain() {
        return FalsifierReview.reconstitute(id, projectId, conclusion, reason, createdAt);
    }
}
