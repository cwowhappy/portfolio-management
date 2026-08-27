package com.portfolio.invest.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface IndustryValuationJpaRepository extends JpaRepository<IndustryValuationJpaEntity, Long> {
    List<IndustryValuationJpaEntity> findByTradingDay(LocalDate tradingDay);
}
