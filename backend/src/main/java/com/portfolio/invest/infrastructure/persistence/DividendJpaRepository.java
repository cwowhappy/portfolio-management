package com.portfolio.invest.infrastructure.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DividendJpaRepository extends JpaRepository<DividendJpaEntity, Long> {
    List<DividendJpaEntity> findByPositionIdOrderByIdAsc(Long positionId);
}
