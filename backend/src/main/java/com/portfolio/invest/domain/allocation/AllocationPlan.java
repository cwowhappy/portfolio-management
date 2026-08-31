package com.portfolio.invest.domain.allocation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** 资产配置方案聚合根：不可变，变更操作返回新实例。权重为百分比，非负且和=100。 */
public final class AllocationPlan {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final Long id;
    private final Long userId;
    private final String name;
    private final PlanSource source;
    private final Map<AssetClass, BigDecimal> weights;
    private final boolean active;
    private final Instant createdAt;
    private final Instant updatedAt;

    private AllocationPlan(Long id, Long userId, String name, PlanSource source,
                           Map<AssetClass, BigDecimal> weights, boolean active,
                           Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.userId = userId;
        this.name = name;
        this.source = source;
        this.weights = Map.copyOf(weights);
        this.active = active;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static AllocationPlan create(Long userId, String name, PlanSource source,
                                        Map<AssetClass, BigDecimal> weights, Instant now) {
        validateWeights(weights);
        return new AllocationPlan(null, userId, name, source, weights, false, now, now);
    }

    public static AllocationPlan reconstitute(Long id, Long userId, String name, PlanSource source,
                                              Map<AssetClass, BigDecimal> weights, boolean active,
                                              Instant createdAt, Instant updatedAt) {
        return new AllocationPlan(id, userId, name, source, weights, active, createdAt, updatedAt);
    }

    public AllocationPlan rename(String newName) {
        return new AllocationPlan(id, userId, newName, source, weights, active, createdAt, Instant.now());
    }

    public AllocationPlan updateWeights(Map<AssetClass, BigDecimal> newWeights) {
        validateWeights(newWeights);
        return new AllocationPlan(id, userId, name, source, newWeights, active, createdAt, Instant.now());
    }

    public AllocationPlan activate() {
        return new AllocationPlan(id, userId, name, source, weights, true, createdAt, Instant.now());
    }

    public AllocationPlan deactivate() {
        return new AllocationPlan(id, userId, name, source, weights, false, createdAt, Instant.now());
    }

    public static void validateWeights(Map<AssetClass, BigDecimal> weights) {
        if (weights == null || weights.isEmpty()) {
            throw new AllocationException(AllocationErrorCode.INVALID_WEIGHTS, "权重不能为空");
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (var w : weights.values()) {
            if (w == null || w.signum() < 0) {
                throw new AllocationException(AllocationErrorCode.INVALID_WEIGHTS, "权重不能为负");
            }
            sum = sum.add(w);
        }
        if (sum.compareTo(HUNDRED) != 0) {
            throw new AllocationException(AllocationErrorCode.INVALID_WEIGHTS, "权重之和必须为 100");
        }
    }

    public Long id() { return id; }
    public Long userId() { return userId; }
    public String name() { return name; }
    public PlanSource source() { return source; }
    public Map<AssetClass, BigDecimal> weights() { return weights; }
    public boolean active() { return active; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
