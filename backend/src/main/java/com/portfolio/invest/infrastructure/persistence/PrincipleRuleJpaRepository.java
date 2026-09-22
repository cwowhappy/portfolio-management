package com.portfolio.invest.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PrincipleRuleJpaRepository extends JpaRepository<PrincipleRuleJpaEntity, Long> {
    List<PrincipleRuleJpaEntity> findByUserIdOrderByMetricAsc(Long userId);
    Optional<PrincipleRuleJpaEntity> findByIdAndUserId(Long id, Long userId);
}
