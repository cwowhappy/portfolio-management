package com.portfolio.invest.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PortfolioJpaRepository extends JpaRepository<PortfolioJpaEntity, Long> {
    Optional<PortfolioJpaEntity> findByUserId(Long userId);
}
