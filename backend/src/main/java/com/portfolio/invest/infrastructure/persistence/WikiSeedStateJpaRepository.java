package com.portfolio.invest.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WikiSeedStateJpaRepository extends JpaRepository<WikiSeedStateJpaEntity, Long> {
    boolean existsByUserId(Long userId);
}
