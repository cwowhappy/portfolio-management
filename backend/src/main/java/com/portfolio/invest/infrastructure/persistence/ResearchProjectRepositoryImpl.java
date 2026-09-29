package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.research.Falsifier;
import com.portfolio.invest.domain.research.ProjectStatus;
import com.portfolio.invest.domain.research.ResearchProject;
import com.portfolio.invest.domain.research.ResearchProjectRepository;
import com.portfolio.invest.domain.research.ResearchStage;
import com.portfolio.invest.domain.research.StageCompletionService.ManualState;
import com.portfolio.invest.domain.research.StrategyDoc;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

// 事务边界在 application 层（P2）；本类不挂 @Transactional（照 AllocationPlanRepositoryImpl 先例）。
@Repository
public class ResearchProjectRepositoryImpl implements ResearchProjectRepository {

    private final ResearchProjectJpaRepository projectJpa;
    private final ResearchStageRecordJpaRepository stageJpa;
    private final ResearchStrategyDocJpaRepository strategyJpa;
    private final ResearchFalsifierJpaRepository falsifierJpa;

    public ResearchProjectRepositoryImpl(ResearchProjectJpaRepository projectJpa,
                                         ResearchStageRecordJpaRepository stageJpa,
                                         ResearchStrategyDocJpaRepository strategyJpa,
                                         ResearchFalsifierJpaRepository falsifierJpa) {
        this.projectJpa = projectJpa;
        this.stageJpa = stageJpa;
        this.strategyJpa = strategyJpa;
        this.falsifierJpa = falsifierJpa;
    }

    @Override
    public List<ResearchProject> findByUserId(Long userId, ProjectStatus status) {
        List<ResearchProjectJpaEntity> entities = status == null
                ? projectJpa.findByUserIdOrderByUpdatedAtDesc(userId)
                : projectJpa.findByUserIdAndStatusOrderByUpdatedAtDesc(userId, status);
        return entities.stream().map(ResearchProjectJpaEntity::toDomain).toList();
    }

    @Override
    public List<ResearchProject> findAllActiveByStage(ResearchStage stage) {
        return projectJpa.findByStatusAndCurrentStageOrderByUpdatedAtDesc(ProjectStatus.ACTIVE, stage)
                .stream().map(ResearchProjectJpaEntity::toDomain).toList();
    }

    @Override
    public Optional<ResearchProject> findById(Long id) {
        return projectJpa.findById(id).map(ResearchProjectJpaEntity::toDomain);
    }

    @Override
    public ResearchProject save(ResearchProject project) {
        // saveAndFlush：唯一索引等约束违例在事务内尽早抛出，供应用层捕获映射（照 UserRepositoryImpl 先例）
        return projectJpa.saveAndFlush(ResearchProjectJpaEntity.fromDomain(project)).toDomain();
    }

    @Override
    public Map<ResearchStage, ManualState> findManualStates(Long projectId) {
        Map<ResearchStage, ManualState> result = new EnumMap<>(ResearchStage.class);
        for (ResearchStageRecordJpaEntity record : stageJpa.findByProjectIdAndManualStateIsNotNull(projectId)) {
            result.put(record.stage(), record.manualState());
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    public void saveManualState(Long projectId, ResearchStage stage, ManualState state) {
        Instant now = Instant.now();
        ResearchStageRecordJpaEntity record = stageJpa.findByProjectIdAndStage(projectId, stage)
                .orElseGet(() -> ResearchStageRecordJpaEntity.of(projectId, stage, state, now));
        record.updateManualState(state, now);
        // saveAndFlush：并发下 (project_id, stage) 唯一键违例事务内早抛
        stageJpa.saveAndFlush(record);
    }

    @Override
    public Optional<StrategyDoc> findStrategy(Long projectId) {
        return strategyJpa.findByProjectId(projectId).map(ResearchStrategyDocJpaEntity::toDomain);
    }

    @Override
    public StrategyDoc saveStrategy(StrategyDoc doc) {
        // saveAndFlush：UNIQUE(project_id) 违例事务内尽早抛出（区别于延迟到提交时冒泡成 500）
        return strategyJpa.saveAndFlush(ResearchStrategyDocJpaEntity.fromDomain(doc)).toDomain();
    }

    @Override
    public List<Falsifier> findFalsifiers(Long strategyId) {
        return falsifierJpa.findByStrategyIdOrderByIdAsc(strategyId).stream()
                .map(ResearchFalsifierJpaEntity::toDomain).toList();
    }

    @Override
    public void saveFalsifiers(Long strategyId, List<Falsifier> falsifiers) {
        falsifierJpa.deleteByStrategyId(strategyId);
        // saveAllAndFlush：FK 等约束违例事务内早抛，与 save 系列同一语义
        falsifierJpa.saveAllAndFlush(falsifiers.stream().map(ResearchFalsifierJpaEntity::fromDomain).toList());
    }
}
