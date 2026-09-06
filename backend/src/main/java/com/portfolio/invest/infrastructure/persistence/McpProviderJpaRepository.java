package com.portfolio.invest.infrastructure.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface McpProviderJpaRepository extends JpaRepository<McpProviderJpaEntity, Long> {
    List<McpProviderJpaEntity> findByEnabledTrueOrderByIdAsc();
}
