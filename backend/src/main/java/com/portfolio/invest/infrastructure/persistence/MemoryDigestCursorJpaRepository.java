package com.portfolio.invest.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MemoryDigestCursorJpaRepository extends JpaRepository<MemoryDigestCursorJpaEntity, Integer> {
}
