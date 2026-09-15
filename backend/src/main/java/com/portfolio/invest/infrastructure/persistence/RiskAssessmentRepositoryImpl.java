package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.allocation.RiskAssessment;
import com.portfolio.invest.domain.allocation.RiskAssessmentRepository;
import java.util.Optional;
import org.springframework.stereotype.Repository;

// 事务边界在 application 层；本类不挂 @Transactional。
@Repository
public class RiskAssessmentRepositoryImpl implements RiskAssessmentRepository {

    private final RiskAssessmentJpaRepository jpa;

    public RiskAssessmentRepositoryImpl(RiskAssessmentJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<RiskAssessment> findByUserId(Long userId) {
        return jpa.findByUserId(userId).map(RiskAssessmentJpaEntity::toDomain);
    }

    @Override
    public RiskAssessment save(RiskAssessment assessment) {
        RiskAssessmentJpaEntity entity = jpa.findByUserId(assessment.userId())
                .map(existing -> RiskAssessmentJpaEntity.fromDomainWithId(existing.getId(), assessment))
                .orElseGet(() -> RiskAssessmentJpaEntity.fromDomain(assessment));
        return jpa.save(entity).toDomain();
    }
}
