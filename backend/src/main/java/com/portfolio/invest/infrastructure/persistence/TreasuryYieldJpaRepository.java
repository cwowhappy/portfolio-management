package com.portfolio.invest.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TreasuryYieldJpaRepository extends JpaRepository<TreasuryYieldJpaEntity, Long> {
    List<TreasuryYieldJpaEntity> findAllByOrderByTradingDayAsc();
}
