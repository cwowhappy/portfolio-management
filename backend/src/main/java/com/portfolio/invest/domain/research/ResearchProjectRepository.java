package com.portfolio.invest.domain.research;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 研究项目仓库端口：聚合根 {@link ResearchProject} 及其附属读写
 * （stage_record 手动覆盖 / strategy_doc / falsifier，S6·D13·D10）。
 * 归属过滤（userId）在用例层双重保障（照 {@code JournalEntryRepository} 先例）。
 */
public interface ResearchProjectRepository {

    /** 按用户查项目；status 为 null 不过滤状态，updatedAt 倒序（走 idx_research_project_user）。 */
    List<ResearchProject> findByUserId(Long userId, ProjectStatus status);

    Optional<ResearchProject> findById(Long id);

    /** 新建或整实体回存；唯一约束等违例由实现保证事务内早抛。 */
    ResearchProject save(ResearchProject project);

    /**
     * 读手动完成度覆盖（S6）：仅含有覆盖的阶段——manual_state 为 NULL 的行不进 Map，
     * 缺项即无覆盖（AUTO 完成度由 {@link StageCompletionService} 读时推导，不落库）。
     */
    Map<ResearchStage, StageCompletionService.ManualState> findManualStates(Long projectId);

    /** upsert 单阶段手动覆盖；{@link StageCompletionService.ManualState#NULL} 表示清除覆盖（行保留、置空）。 */
    void saveManualState(Long projectId, ResearchStage stage, StageCompletionService.ManualState state);

    Optional<StrategyDoc> findStrategy(Long projectId);

    /** 新建或回存策略文档；同项目已有文档时新建（id=null）会撞 UNIQUE(project_id) 早抛。 */
    StrategyDoc saveStrategy(StrategyDoc doc);

    List<Falsifier> findFalsifiers(Long strategyId);

    /** 整替该策略全部证伪条件（先清空再插入）。 */
    void saveFalsifiers(Long strategyId, List<Falsifier> falsifiers);
}
