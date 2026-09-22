package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.wiki.PrincipleRule;
import com.portfolio.invest.domain.wiki.PrincipleRuleRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class PrincipleRuleRepositoryImpl implements PrincipleRuleRepository {

    private final PrincipleRuleJpaRepository jpa;

    public PrincipleRuleRepositoryImpl(PrincipleRuleJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public List<PrincipleRule> findByUserId(Long userId) {
        return jpa.findByUserIdOrderByMetricAsc(userId).stream().map(PrincipleRuleJpaEntity::toDomain).toList();
    }

    @Override
    public Optional<PrincipleRule> findByIdAndUserId(Long id, Long userId) {
        return jpa.findByIdAndUserId(id, userId).map(PrincipleRuleJpaEntity::toDomain);
    }

    @Override
    public PrincipleRule save(PrincipleRule rule) {
        return jpa.saveAndFlush(PrincipleRuleJpaEntity.fromDomain(rule)).toDomain();
    }

    @Override
    public void deleteById(Long id) {
        jpa.deleteById(id);
    }
}
