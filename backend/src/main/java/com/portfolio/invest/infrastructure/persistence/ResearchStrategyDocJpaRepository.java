package com.portfolio.invest.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResearchStrategyDocJpaRepository extends JpaRepository<ResearchStrategyDocJpaEntity, Long> {
    Optional<ResearchStrategyDocJpaEntity> findByProjectId(Long projectId);
}
