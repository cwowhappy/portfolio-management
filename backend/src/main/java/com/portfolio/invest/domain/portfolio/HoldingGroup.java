package com.portfolio.invest.domain.portfolio;

import java.time.Instant;

public final class HoldingGroup {

    private final Long id;
    private final Long portfolioId;
    private final String name;
    private final GroupType type;
    private final Instant createdAt;
    private final Long version;

    private HoldingGroup(Long id, Long portfolioId, String name, GroupType type, Instant createdAt, Long version) {
        this.id = id;
        this.portfolioId = portfolioId;
        this.name = name;
        this.type = type;
        this.createdAt = createdAt;
        this.version = version;
    }

    public static HoldingGroup create(Long portfolioId, String name, GroupType type, Instant now) {
        return new HoldingGroup(null, portfolioId, name, type, now, null);
    }

    public static HoldingGroup reconstitute(Long id, Long portfolioId, String name, GroupType type, Instant createdAt) {
        return reconstitute(id, portfolioId, name, type, createdAt, null);
    }

    public static HoldingGroup reconstitute(Long id, Long portfolioId, String name, GroupType type,
                                            Instant createdAt, Long version) {
        return new HoldingGroup(id, portfolioId, name, type, createdAt, version);
    }

    public HoldingGroup rename(String newName) {
        return new HoldingGroup(id, portfolioId, newName, type, createdAt, version);
    }

    public Long id() { return id; }
    public Long portfolioId() { return portfolioId; }
    public String name() { return name; }
    public GroupType type() { return type; }
    public Instant createdAt() { return createdAt; }
    public Long version() { return version; }
}
