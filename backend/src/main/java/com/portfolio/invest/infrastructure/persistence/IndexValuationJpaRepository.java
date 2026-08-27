package com.portfolio.invest.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IndexValuationJpaRepository extends JpaRepository<IndexValuationJpaEntity, Long> {
    List<IndexValuationJpaEntity> findByIndexCodeOrderByTradingDayAsc(String indexCode);
}
