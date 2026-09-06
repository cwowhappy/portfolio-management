package com.portfolio.invest.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface McpUserConfigJpaRepository extends JpaRepository<McpUserConfigJpaEntity, Long> {
    List<McpUserConfigJpaEntity> findByUserId(Long userId);
    Optional<McpUserConfigJpaEntity> findByUserIdAndProviderId(Long userId, Long providerId);
    void deleteByUserIdAndProviderId(Long userId, Long providerId);
}
