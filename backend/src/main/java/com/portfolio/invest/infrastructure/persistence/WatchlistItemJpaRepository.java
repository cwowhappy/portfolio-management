package com.portfolio.invest.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WatchlistItemJpaRepository extends JpaRepository<WatchlistItemJpaEntity, Long> {

    List<WatchlistItemJpaEntity> findByUserIdOrderByAddedAtDesc(Long userId);

    boolean existsByUserIdAndStockCode(Long userId, String stockCode);

    long countByUserId(Long userId);

    void deleteByUserIdAndStockCode(Long userId, String stockCode);
}
