package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.research.ResearchFeedback;
import com.portfolio.invest.domain.research.ResearchStage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** 模板改进建议行（research_feedback，append-only 只收集：无 version/updated_at、无 update 用例）。 */
@Entity
@Table(name = "research_feedback")
public class ResearchFeedbackJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 来源复盘软引用（可空，无 FK 不级联）。 */
    @Column(name = "review_id")
    private Long reviewId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ResearchStage stage;

    @Column(nullable = false, length = 1000)
    private String content;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ResearchFeedbackJpaEntity() {}

    public static ResearchFeedbackJpaEntity fromDomain(ResearchFeedback f) {
        ResearchFeedbackJpaEntity entity = new ResearchFeedbackJpaEntity();
        entity.id = f.id();
        entity.projectId = f.projectId();
        entity.reviewId = f.reviewId();
        entity.stage = f.stage();
        entity.content = f.content();
        entity.createdAt = f.createdAt();
        return entity;
    }

    public ResearchFeedback toDomain() {
        return ResearchFeedback.reconstitute(id, projectId, reviewId, stage, content, createdAt);
    }
}
