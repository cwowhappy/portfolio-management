package com.portfolio.invest.infrastructure.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeJpaRepository extends JpaRepository<TradeJpaEntity, Long> {
    List<TradeJpaEntity> findByPositionIdOrderByIdAsc(Long positionId);
}
