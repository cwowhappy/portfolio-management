package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.screening.WatchlistItem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "watchlist_item")
public class WatchlistItemJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "stock_code", nullable = false, length = 16)
    private String stockCode;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    protected WatchlistItemJpaEntity() {}

    public static WatchlistItemJpaEntity fromDomain(WatchlistItem item) {
        WatchlistItemJpaEntity e = new WatchlistItemJpaEntity();
        e.id = item.id();
        e.userId = item.userId();
        e.stockCode = item.stockCode();
        e.addedAt = item.addedAt();
        return e;
    }

    public WatchlistItem toDomain() {
        return new WatchlistItem(id, userId, stockCode, addedAt);
    }
}
