package com.portfolio.invest.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "wiki_seed_state")
public class WikiSeedStateJpaEntity {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "seeded_at", nullable = false)
    private Instant seededAt;

    protected WikiSeedStateJpaEntity() {}

    public static WikiSeedStateJpaEntity of(Long userId) {
        WikiSeedStateJpaEntity e = new WikiSeedStateJpaEntity();
        e.userId = userId;
        e.seededAt = Instant.now();
        return e;
    }
}
