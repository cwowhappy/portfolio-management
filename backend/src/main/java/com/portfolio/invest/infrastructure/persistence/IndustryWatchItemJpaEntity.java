package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.industry.IndustryWatchItem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "industry_watch")
public class IndustryWatchItemJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "industry_code", nullable = false, length = 16)
    private String industryCode;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    protected IndustryWatchItemJpaEntity() {}

    public static IndustryWatchItemJpaEntity fromDomain(IndustryWatchItem item) {
        IndustryWatchItemJpaEntity e = new IndustryWatchItemJpaEntity();
        e.id = item.id();
        e.userId = item.userId();
        e.industryCode = item.industryCode();
        e.addedAt = item.addedAt();
        return e;
    }

    public IndustryWatchItem toDomain() {
        return new IndustryWatchItem(id, userId, industryCode, addedAt);
    }
}
