package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.allocation.AssetClass;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "allocation_plan_weight")
public class AllocationPlanWeightJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_class", nullable = false, length = 16)
    private AssetClass assetClass;

    @Column(nullable = false)
    private BigDecimal weight;

    protected AllocationPlanWeightJpaEntity() {}

    public static AllocationPlanWeightJpaEntity fromDomain(Long planId, AssetClass assetClass, BigDecimal weight) {
        AllocationPlanWeightJpaEntity e = new AllocationPlanWeightJpaEntity();
        e.planId = planId;
        e.assetClass = assetClass;
        e.weight = weight;
        return e;
    }

    AssetClass assetClass() { return assetClass; }
    BigDecimal weight() { return weight; }
}
