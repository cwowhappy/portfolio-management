package com.portfolio.invest.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HoldingGroupJpaRepository extends JpaRepository<HoldingGroupJpaEntity, Long> {
    List<HoldingGroupJpaEntity> findByPortfolioIdOrderByIdAsc(Long portfolioId);
    Optional<HoldingGroupJpaEntity> findByIdAndPortfolioId(Long id, Long portfolioId);
}
