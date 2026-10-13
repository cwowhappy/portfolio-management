package com.portfolio.invest.application.research;

import static com.portfolio.invest.application.research.ResearchServiceSupport.requireProject;

import com.portfolio.invest.application.research.ResearchViews.EntryPlanView;
import com.portfolio.invest.domain.research.EntryBatch;
import com.portfolio.invest.domain.research.EntryPlan;
import com.portfolio.invest.domain.research.ResearchEntryPlanRepository;
import com.portfolio.invest.domain.research.ResearchErrorCode;
import com.portfolio.invest.domain.research.ResearchException;
import com.portfolio.invest.domain.research.ResearchProjectRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * 建仓计划用例族（F09，P3-T4）。2026-10-12 按用例族拆分自原
 * {@link ResearchApplicationService}（纯搬移，行为不变）；事务边界在门面方法上，
 * 本类为门面手工装配、非 Spring bean。
 */
public class ResearchEntryPlanApplicationService {

    private final ResearchProjectRepository repository;
    private final ResearchEntryPlanRepository entryPlanRepository;
    private final Clock clock;

    public ResearchEntryPlanApplicationService(ResearchProjectRepository repository,
                                               ResearchEntryPlanRepository entryPlanRepository,
                                               Clock clock) {
        this.repository = repository;
        this.entryPlanRepository = entryPlanRepository;
        this.clock = clock;
    }

    /** 建仓计划查询：未保存 → NOT_FOUND（照 getStrategy 先例）。 */
    public EntryPlanView getEntryPlan(Long userId, Long projectId) {
        requireProject(repository, userId, projectId);
        return EntryPlanView.from(requireEntryPlan(projectId));
    }

    /**
     * 建仓计划整替保存：每次 {@link EntryPlan#of} 重建（T1 裁定无 wither），plan+batches 同事务整替；
     * Σratio &gt; 1 唯一硬拒绝（RATIO_SUM_EXCEEDED→422，D5），kellyRatio 读时计算不落库。
     */
    public EntryPlanView saveEntryPlan(Long userId, Long projectId,
                                       ResearchApplicationService.SaveEntryPlanCommand cmd) {
        requireProject(repository, userId, projectId);
        EntryPlan plan = EntryPlan.of(projectId, cmd.winRate(), cmd.payoffRatio(), toBatches(cmd.batches()),
                clock.instant());
        return EntryPlanView.from(entryPlanRepository.save(plan));
    }

    private EntryPlan requireEntryPlan(Long projectId) {
        return entryPlanRepository.findByProjectId(projectId)
                .orElseThrow(() -> new ResearchException(ResearchErrorCode.NOT_FOUND, "建仓计划不存在"));
    }

    /** wire 批次项 → 领域批次（T1 deferred 守卫：null 列表视同空、null 元素显式拒绝，均不落库）。 */
    private static List<EntryBatch> toBatches(List<ResearchApplicationService.SaveEntryBatchItem> items) {
        if (items == null) {
            return List.of();
        }
        List<EntryBatch> batches = new ArrayList<>(items.size());
        for (ResearchApplicationService.SaveEntryBatchItem item : items) {
            if (item == null) {
                throw new ResearchException(ResearchErrorCode.BATCH_INVALID, "批次条目不能为空");
            }
            batches.add(new EntryBatch(item.seq(), item.priceLow(), item.priceHigh(),
                    item.quantity(), item.amount(), item.ratio()));
        }
        return batches;
    }
}
