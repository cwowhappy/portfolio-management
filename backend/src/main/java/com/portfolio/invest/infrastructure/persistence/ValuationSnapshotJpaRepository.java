package com.portfolio.invest.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ValuationSnapshotJpaRepository extends JpaRepository<ValuationSnapshotJpaEntity, Long> {

    ValuationSnapshotJpaEntity findTopByOrderByTradingDayDesc();

    java.util.List<ValuationSnapshotJpaEntity> findAllByOrderByTradingDayAsc();
}
