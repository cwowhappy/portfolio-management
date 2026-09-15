package com.portfolio.invest.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RiskAssessmentJpaRepository extends JpaRepository<RiskAssessmentJpaEntity, Long> {
    Optional<RiskAssessmentJpaEntity> findByUserId(Long userId);
}
