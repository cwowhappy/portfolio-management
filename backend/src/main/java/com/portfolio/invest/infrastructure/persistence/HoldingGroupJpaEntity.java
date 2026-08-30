package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.portfolio.GroupType;
import com.portfolio.invest.domain.portfolio.HoldingGroup;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "holding_group")
public class HoldingGroupJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "portfolio_id", nullable = false)
    private Long portfolioId;

    @Column(nullable = false, length = 64)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private GroupType type;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected HoldingGroupJpaEntity() {}

    public static HoldingGroupJpaEntity fromDomain(HoldingGroup g) {
        HoldingGroupJpaEntity e = new HoldingGroupJpaEntity();
        e.id = g.id();
        e.portfolioId = g.portfolioId();
        e.name = g.name();
        e.type = g.type();
        e.createdAt = g.createdAt();
        return e;
    }

    public HoldingGroup toDomain() {
        return HoldingGroup.reconstitute(id, portfolioId, name, type, createdAt);
    }
}
