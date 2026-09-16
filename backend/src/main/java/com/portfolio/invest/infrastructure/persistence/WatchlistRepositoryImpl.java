package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.screening.WatchlistItem;
import com.portfolio.invest.domain.screening.WatchlistRepository;
import java.util.List;
import org.springframework.stereotype.Repository;

// 事务边界在 application 层；本类不挂 @Transactional。
@Repository
public class WatchlistRepositoryImpl implements WatchlistRepository {

    private final WatchlistItemJpaRepository jpa;

    public WatchlistRepositoryImpl(WatchlistItemJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public List<WatchlistItem> findByUserId(Long userId) {
        return jpa.findByUserIdOrderByAddedAtDesc(userId).stream().map(WatchlistItemJpaEntity::toDomain).toList();
    }

    @Override
    public boolean existsByUserIdAndStockCode(Long userId, String stockCode) {
        return jpa.existsByUserIdAndStockCode(userId, stockCode);
    }

    @Override
    public long countByUserId(Long userId) {
        return jpa.countByUserId(userId);
    }

    @Override
    public WatchlistItem save(WatchlistItem item) {
        return jpa.save(WatchlistItemJpaEntity.fromDomain(item)).toDomain();
    }

    @Override
    public void deleteByUserIdAndStockCode(Long userId, String stockCode) {
        jpa.deleteByUserIdAndStockCode(userId, stockCode);
    }
}
