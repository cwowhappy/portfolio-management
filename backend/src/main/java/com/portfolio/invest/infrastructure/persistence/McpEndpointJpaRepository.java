package com.portfolio.invest.infrastructure.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface McpEndpointJpaRepository extends JpaRepository<McpEndpointJpaEntity, Long> {
    List<McpEndpointJpaEntity> findByProviderIdAndEnabledTrueOrderByIdAsc(Long providerId);
    boolean existsByProviderIdAndUrl(Long providerId, String url);
}
