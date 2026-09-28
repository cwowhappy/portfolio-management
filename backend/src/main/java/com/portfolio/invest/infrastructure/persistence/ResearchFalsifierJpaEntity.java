package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.research.Falsifier;
import com.portfolio.invest.domain.research.FalsifierKind;
import com.portfolio.invest.domain.research.FalsifierPredicate;
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
@Table(name = "research_falsifier")
public class ResearchFalsifierJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "strategy_id", nullable = false)
    private Long strategyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FalsifierKind kind;

    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private FalsifierPredicate predicate;

    @Column(precision = 12, scale = 4)
    private BigDecimal threshold;

    @Column(name = "event_checked")
    private boolean eventChecked;

    @Column(length = 500)
    private String note;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected ResearchFalsifierJpaEntity() {}

    public static ResearchFalsifierJpaEntity fromDomain(Falsifier f) {
        ResearchFalsifierJpaEntity entity = new ResearchFalsifierJpaEntity();
        entity.id = f.id();
        entity.strategyId = f.strategyId();
        entity.kind = f.kind();
        entity.predicate = f.predicate();
        entity.threshold = f.threshold();
        entity.eventChecked = f.eventChecked();
        entity.note = f.note();
        entity.enabled = f.enabled();
        entity.createdAt = f.createdAt();
        entity.updatedAt = f.updatedAt();
        entity.version = f.version();
        return entity;
    }

    public Falsifier toDomain() {
        return Falsifier.reconstitute(id, strategyId, kind, predicate, threshold, eventChecked,
                note, enabled, version, createdAt, updatedAt);
    }
}
