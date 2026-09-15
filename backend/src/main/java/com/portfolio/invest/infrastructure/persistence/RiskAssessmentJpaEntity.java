package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.allocation.RiskAssessment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "risk_assessment")
public class RiskAssessmentJpaEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;
    @Column(name = "total_score", nullable = false)
    private int totalScore;
    @Column(nullable = false)
    private String profile;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, String> answers;
    @Column(name = "assessed_at", nullable = false)
    private Instant assessedAt;

    protected RiskAssessmentJpaEntity() {}

    public static RiskAssessmentJpaEntity fromDomain(RiskAssessment a) {
        return withValues(null, a);
    }

    public static RiskAssessmentJpaEntity fromDomainWithId(Long id, RiskAssessment a) {
        return withValues(id, a);
    }

    private static RiskAssessmentJpaEntity withValues(Long id, RiskAssessment a) {
        RiskAssessmentJpaEntity e = new RiskAssessmentJpaEntity();
        e.id = id;
        e.userId = a.userId();
        e.totalScore = a.totalScore();
        e.profile = a.profile().name();
        e.answers = a.answers();
        e.assessedAt = a.assessedAt();
        return e;
    }

    public Long getId() { return id; }

    public RiskAssessment toDomain() {
        return RiskAssessment.reconstitute(userId, totalScore,
                com.portfolio.invest.domain.allocation.RiskProfile.valueOf(profile), answers, assessedAt);
    }
}
