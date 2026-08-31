package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.allocation.AllocationPlan;
import com.portfolio.invest.domain.allocation.AllocationPlanRepository;
import com.portfolio.invest.domain.allocation.AssetClass;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;

// 事务边界在 application 层（P2）；本类不挂 @Transactional。
@Repository
public class AllocationPlanRepositoryImpl implements AllocationPlanRepository {

    private final AllocationPlanJpaRepository planJpa;
    private final AllocationPlanWeightJpaRepository weightJpa;

    public AllocationPlanRepositoryImpl(AllocationPlanJpaRepository planJpa,
                                        AllocationPlanWeightJpaRepository weightJpa) {
        this.planJpa = planJpa;
        this.weightJpa = weightJpa;
    }

    @Override
    public List<AllocationPlan> findByUserId(Long userId) {
        return planJpa.findByUserIdOrderByIdDesc(userId).stream().map(this::toDomain).toList();
    }

    @Override
    public Optional<AllocationPlan> findByIdAndUserId(Long id, Long userId) {
        return planJpa.findByIdAndUserId(id, userId).map(this::toDomain);
    }

    @Override
    public Optional<AllocationPlan> findActiveByUserId(Long userId) {
        return planJpa.findByUserIdAndActive(userId, true).map(this::toDomain);
    }

    @Override
    public AllocationPlan save(AllocationPlan plan) {
        AllocationPlanJpaEntity saved = planJpa.save(AllocationPlanJpaEntity.fromDomain(plan));
        weightJpa.deleteByPlanId(saved.getId());
        plan.weights().forEach((ac, w) ->
                weightJpa.save(AllocationPlanWeightJpaEntity.fromDomain(saved.getId(), ac, w)));
        return toDomain(saved);
    }

    @Override
    public void deactivateAllByUserId(Long userId) {
        planJpa.deactivateAllByUserId(userId);
    }

    @Override
    public void deleteById(Long id) {
        planJpa.deleteById(id);
    }

    private AllocationPlan toDomain(AllocationPlanJpaEntity e) {
        Map<AssetClass, BigDecimal> weights = weightJpa.findByPlanId(e.getId()).stream()
                .collect(Collectors.toMap(AllocationPlanWeightJpaEntity::assetClass,
                        AllocationPlanWeightJpaEntity::weight));
        return e.toDomain(weights);
    }
}
