package com.portfolio.invest.domain.portfolio;

import java.time.Instant;

public final class Portfolio {

    private final Long id;
    private final Long userId;
    private final CostMethod costMethod;
    private final Instant createdAt;
    private final Instant updatedAt;

    private Portfolio(Long id, Long userId, CostMethod costMethod, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.userId = userId;
        this.costMethod = costMethod;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Portfolio reconstitute(Long id, Long userId, CostMethod costMethod,
                                         Instant createdAt, Instant updatedAt) {
        return new Portfolio(id, userId, costMethod, createdAt, updatedAt);
    }

    public Long id() { return id; }
    public Long userId() { return userId; }
    public CostMethod costMethod() { return costMethod; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
