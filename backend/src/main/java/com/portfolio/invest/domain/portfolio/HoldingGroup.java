package com.portfolio.invest.domain.portfolio;

import java.time.Instant;

public final class HoldingGroup {

    private final Long id;
    private final Long portfolioId;
    private final String name;
    private final GroupType type;
    private final Instant createdAt;

    private HoldingGroup(Long id, Long portfolioId, String name, GroupType type, Instant createdAt) {
        this.id = id;
        this.portfolioId = portfolioId;
        this.name = name;
        this.type = type;
        this.createdAt = createdAt;
    }

    public static HoldingGroup create(Long portfolioId, String name, GroupType type, Instant now) {
        return new HoldingGroup(null, portfolioId, name, type, now);
    }

    public static HoldingGroup reconstitute(Long id, Long portfolioId, String name, GroupType type, Instant createdAt) {
        return new HoldingGroup(id, portfolioId, name, type, createdAt);
    }

    public Long id() { return id; }
    public Long portfolioId() { return portfolioId; }
    public String name() { return name; }
    public GroupType type() { return type; }
    public Instant createdAt() { return createdAt; }
}
