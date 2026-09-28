package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.research.EntryBatch;
import com.portfolio.invest.domain.research.EntryPlan;
import com.portfolio.invest.domain.research.ResearchEntryPlanRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

// 事务边界在 application 层（P2 先例）；本类不挂 @Transactional（照 ResearchProjectRepositoryImpl）。
@Repository
public class ResearchEntryPlanRepositoryImpl implements ResearchEntryPlanRepository {

    private final ResearchEntryPlanJpaRepository planJpa;
    private final ResearchEntryBatchJpaRepository batchJpa;

    public ResearchEntryPlanRepositoryImpl(ResearchEntryPlanJpaRepository planJpa,
                                           ResearchEntryBatchJpaRepository batchJpa) {
        this.planJpa = planJpa;
        this.batchJpa = batchJpa;
    }

    @Override
    public Optional<EntryPlan> findByProjectId(Long projectId) {
        return planJpa.findByProjectId(projectId).map(this::toDomainWithBatches);
    }

    /**
     * 整替保存：batches 先删再删 plan（FK 悬挂防护），随后插入新 plan+batches。
     * delete 走 @Modifying 直删（执行前自动 flush 待插行，照 falsifier 整替先例）。
     */
    @Override
    public EntryPlan save(EntryPlan plan) {
        planJpa.findByProjectId(plan.projectId()).ifPresent(existing ->
                batchJpa.deleteByPlanId(existing.getId()));
        planJpa.deleteByProjectId(plan.projectId());
        ResearchEntryPlanJpaEntity saved = planJpa.saveAndFlush(
                ResearchEntryPlanJpaEntity.fromDomain(plan));
        List<ResearchEntryBatchJpaEntity> batches = plan.batches().stream()
                .map(batch -> ResearchEntryBatchJpaEntity.of(saved.getId(), batch))
                .toList();
        List<EntryBatch> domainBatches = batchJpa.saveAllAndFlush(batches).stream()
                .map(ResearchEntryBatchJpaEntity::toDomain)
                .toList();
        return EntryPlan.reconstitute(saved.getId(), saved.getProjectId(), saved.getWinRate(),
                saved.getPayoffRatio(), domainBatches, saved.getVersion(),
                saved.getCreatedAt(), saved.getUpdatedAt());
    }

    private EntryPlan toDomainWithBatches(ResearchEntryPlanJpaEntity entity) {
        List<EntryBatch> batches = batchJpa.findByPlanIdOrderBySeqAsc(entity.getId()).stream()
                .map(ResearchEntryBatchJpaEntity::toDomain)
                .toList();
        return EntryPlan.reconstitute(entity.getId(), entity.getProjectId(), entity.getWinRate(),
                entity.getPayoffRatio(), batches, entity.getVersion(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
