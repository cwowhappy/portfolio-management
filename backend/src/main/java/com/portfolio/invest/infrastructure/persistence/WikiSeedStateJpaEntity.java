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

    /** SOP 模板 seeding 独立标记（V2 加列，可空：未 seed 过为 null）。 */
    @Column(name = "sop_seeded_at")
    private Instant sopSeededAt;

    protected WikiSeedStateJpaEntity() {}

    public static WikiSeedStateJpaEntity of(Long userId) {
        WikiSeedStateJpaEntity e = new WikiSeedStateJpaEntity();
        e.userId = userId;
        e.seededAt = Instant.now();
        return e;
    }

    public void markSopSeeded() {
        this.sopSeededAt = Instant.now();
    }
}
