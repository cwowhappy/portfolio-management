package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.allocation.AllocationPlan;
import com.portfolio.invest.domain.allocation.AssetClass;
import com.portfolio.invest.domain.allocation.PlanSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "allocation_plan")
public class AllocationPlanJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 64)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PlanSource source;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AllocationPlanJpaEntity() {}

    public static AllocationPlanJpaEntity fromDomain(AllocationPlan p) {
        AllocationPlanJpaEntity e = new AllocationPlanJpaEntity();
        e.id = p.id();
        e.userId = p.userId();
        e.name = p.name();
        e.source = p.source();
        e.active = p.active();
        e.createdAt = p.createdAt();
        e.updatedAt = p.updatedAt();
        return e;
    }

    public AllocationPlan toDomain(Map<AssetClass, BigDecimal> weights) {
        return AllocationPlan.reconstitute(id, userId, name, source, weights, active, createdAt, updatedAt);
    }

    Long getId() { return id; }
}
