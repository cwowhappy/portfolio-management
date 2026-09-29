package com.portfolio.invest.domain.research;

import java.util.Optional;

/**
 * 建仓计划仓库端口（M16-F09，P3-T4）：plan + batches 一体整替
 * （T1 裁定：无 wither，PUT = 每次 {@link EntryPlan#of} 重建后整替落库）。
 * 归属过滤在用例层双重保障（照 {@link ResearchProjectRepository} 先例）。
 */
public interface ResearchEntryPlanRepository {

    Optional<EntryPlan> findByProjectId(Long projectId);

    /**
     * 整替保存：先删该项目既有 plan 与全部 batches，再插入新 plan+batches
     * （同事务由 application 用例边界保证）；返回带 id 的持久化形态。
     */
    EntryPlan save(EntryPlan plan);
}
