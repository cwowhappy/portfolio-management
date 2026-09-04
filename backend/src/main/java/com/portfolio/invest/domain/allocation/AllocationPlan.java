package com.portfolio.invest.domain.allocation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** 资产配置方案聚合根：不可变，变更操作返回新实例。权重为百分比，非负且和=100。 */
public final class AllocationPlan {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    /** 权重和容差：DB NUMERIC(18,4) 四舍五入后读回值可能非精确 100（如 33.3333×3=99.9999），允许 0.01 内偏差。 */
    static final BigDecimal WEIGHT_SUM_TOLERANCE = new BigDecimal("0.01");

    private final Long id;
    private final Long userId;
    private final String name;
    private final PlanSource source;
    private final Map<AssetClass, BigDecimal> weights;
    private final boolean active;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final Long version;

    private AllocationPlan(Long id, Long userId, String name, PlanSource source,
                           Map<AssetClass, BigDecimal> weights, boolean active,
                           Instant createdAt, Instant updatedAt, Long version) {
        this.id = id;
        this.userId = userId;
        this.name = name;
        this.source = source;
        this.weights = Map.copyOf(weights);
        this.active = active;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public static AllocationPlan create(Long userId, String name, PlanSource source,
                                        Map<AssetClass, BigDecimal> weights, Instant now) {
        validateWeights(weights);
        return new AllocationPlan(null, userId, name, source, weights, false, now, now, null);
    }

    public static AllocationPlan reconstitute(Long id, Long userId, String name, PlanSource source,
                                              Map<AssetClass, BigDecimal> weights, boolean active,
                                              Instant createdAt, Instant updatedAt) {
        return reconstitute(id, userId, name, source, weights, active, createdAt, updatedAt, null);
    }

    public static AllocationPlan reconstitute(Long id, Long userId, String name, PlanSource source,
                                              Map<AssetClass, BigDecimal> weights, boolean active,
                                              Instant createdAt, Instant updatedAt, Long version) {
        return new AllocationPlan(id, userId, name, source, weights, active, createdAt, updatedAt, version);
    }

    public AllocationPlan rename(String newName) {
        return new AllocationPlan(id, userId, newName, source, weights, active, createdAt, Instant.now(), version);
    }

    public AllocationPlan updateWeights(Map<AssetClass, BigDecimal> newWeights) {
        validateWeights(newWeights);
        return new AllocationPlan(id, userId, name, source, newWeights, active, createdAt, Instant.now(), version);
    }

    public AllocationPlan activate() {
        return new AllocationPlan(id, userId, name, source, weights, true, createdAt, Instant.now(), version);
    }

    public AllocationPlan deactivate() {
        return new AllocationPlan(id, userId, name, source, weights, false, createdAt, Instant.now(), version);
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
        if (HUNDRED.subtract(sum).abs().compareTo(WEIGHT_SUM_TOLERANCE) > 0) {
            throw new AllocationException(AllocationErrorCode.INVALID_WEIGHTS, "权重之和必须为 100（容差 ±0.01）");
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
    public Long version() { return version; }
}
