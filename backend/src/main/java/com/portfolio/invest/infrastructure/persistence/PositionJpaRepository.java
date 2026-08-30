package com.portfolio.invest.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PositionJpaRepository extends JpaRepository<PositionJpaEntity, Long> {
    List<PositionJpaEntity> findByPortfolioId(Long portfolioId);
    List<PositionJpaEntity> findByGroupId(Long groupId);
    Optional<PositionJpaEntity> findByIdAndPortfolioId(Long id, Long portfolioId);
    Optional<PositionJpaEntity> findByPortfolioIdAndGroupIdAndStockCode(Long portfolioId, Long groupId, String stockCode);
}
