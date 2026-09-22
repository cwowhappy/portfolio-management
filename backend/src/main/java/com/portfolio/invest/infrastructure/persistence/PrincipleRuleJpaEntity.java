package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.wiki.PrincipleMetric;
import com.portfolio.invest.domain.wiki.PrincipleRule;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "principle_rule")
public class PrincipleRuleJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PrincipleMetric metric;

    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal threshold;

    @Column(nullable = false)
    private boolean enabled;

    @Column(length = 500)
    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected PrincipleRuleJpaEntity() {}

    public static PrincipleRuleJpaEntity fromDomain(PrincipleRule r) {
        PrincipleRuleJpaEntity entity = new PrincipleRuleJpaEntity();
        entity.id = r.id();
        entity.userId = r.userId();
        entity.metric = r.metric();
        entity.threshold = r.threshold();
        entity.enabled = r.enabled();
        entity.description = r.description();
        entity.createdAt = r.createdAt();
        entity.updatedAt = r.updatedAt();
        entity.version = r.version();
        return entity;
    }

    public PrincipleRule toDomain() {
        return PrincipleRule.reconstitute(id, userId, metric, threshold, enabled, description,
                createdAt, updatedAt, version);
    }
}
