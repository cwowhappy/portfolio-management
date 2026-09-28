package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.research.ProjectStatus;
import com.portfolio.invest.domain.research.ResearchProject;
import com.portfolio.invest.domain.research.ResearchStage;
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

@Entity
@Table(name = "research_project")
public class ResearchProjectJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "stock_code", nullable = false, length = 16)
    private String stockCode;

    @Column(name = "stock_name", nullable = false, length = 64)
    private String stockName;

    @Column(name = "industry_code", length = 16)
    private String industryCode;

    @Column(nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_stage", nullable = false, length = 32)
    private ResearchStage currentStage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ProjectStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected ResearchProjectJpaEntity() {}

    public static ResearchProjectJpaEntity fromDomain(ResearchProject p) {
        ResearchProjectJpaEntity entity = new ResearchProjectJpaEntity();
        entity.id = p.id();
        entity.userId = p.userId();
        entity.stockCode = p.stockCode();
        entity.stockName = p.stockName();
        entity.industryCode = p.industryCode();
        entity.title = p.title();
        entity.currentStage = p.currentStage();
        entity.status = p.status();
        entity.createdAt = p.createdAt();
        entity.updatedAt = p.updatedAt();
        entity.version = p.version();
        return entity;
    }

    public ResearchProject toDomain() {
        return ResearchProject.reconstitute(id, userId, stockCode, stockName, industryCode, title,
                currentStage, status, version, createdAt, updatedAt);
    }
}
