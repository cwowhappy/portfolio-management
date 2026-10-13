package com.portfolio.invest.application.research;

import static com.portfolio.invest.application.research.ResearchServiceSupport.requireProject;
import static com.portfolio.invest.application.research.ResearchServiceSupport.writeEvent;

import com.portfolio.invest.application.research.ResearchViews.StrategyView;
import com.portfolio.invest.domain.journal.JournalEntryRepository;
import com.portfolio.invest.domain.research.ResearchErrorCode;
import com.portfolio.invest.domain.research.ResearchException;
import com.portfolio.invest.domain.research.ResearchProject;
import com.portfolio.invest.domain.research.ResearchProjectRepository;
import com.portfolio.invest.domain.research.StrategyDoc;
import com.portfolio.invest.domain.research.StrategyState;
import java.time.Clock;

/**
 * 策略用例族（D13 两级状态机：草稿暂存 / 定稿 / 修订）。2026-10-12 按用例族拆分自原
 * {@link ResearchApplicationService}（纯搬移，行为不变）；事务边界在门面方法上，
 * 本类为门面手工装配、非 Spring bean。
 */
public class ResearchStrategyApplicationService {

    private final ResearchProjectRepository repository;
    private final JournalEntryRepository journalRepository;
    private final Clock clock;

    public ResearchStrategyApplicationService(ResearchProjectRepository repository,
                                              JournalEntryRepository journalRepository,
                                              Clock clock) {
        this.repository = repository;
        this.journalRepository = journalRepository;
        this.clock = clock;
    }

    /** 策略查询：未创建过草稿 → NOT_FOUND（详情视图以 strategy=null 表达同一事实）。 */
    public StrategyView getStrategy(Long userId, Long projectId) {
        requireProject(repository, userId, projectId);
        return StrategyView.from(requireStrategy(projectId));
    }

    /** 暂存草稿（D13）：无文档时以 draftOf 新建；FINALIZED 态拒绝（须先 revise）。 */
    public StrategyView saveStrategyDraft(Long userId, Long projectId, SaveStrategyCommand cmd) {
        requireProject(repository, userId, projectId);
        StrategyDoc existing = repository.findStrategy(projectId)
                .orElseGet(() -> StrategyDoc.draftOf(projectId, clock.instant()));
        StrategyDoc saved = repository.saveStrategy(existing.saveDraft(cmd.thesis(), cmd.valuationLow(),
                cmd.valuationHigh(), cmd.positionPlan(), cmd.buyConditions(), cmd.riskNotes(),
                clock.instant()));
        return StrategyView.from(saved);
    }

    /** 定稿（D13 显式动作）：估值区间校验 domain 先拦（VALUATION_RANGE_INVALID→422）；写「策略定稿」事件。 */
    public StrategyView finalizeStrategy(Long userId, Long projectId) {
        ResearchProject project = requireProject(repository, userId, projectId);
        StrategyDoc doc = requireStrategy(projectId);
        StrategyDoc finalized = repository.saveStrategy(doc.finalizeDoc(clock.instant()));
        if (doc.state() != StrategyState.FINALIZED) {
            writeEvent(journalRepository, clock, project, "策略定稿",
                    "策略文档定稿，估值区间 " + finalized.valuationLow() + " ~ " + finalized.valuationHigh());
        }
        return StrategyView.from(finalized);
    }

    /** 修订（D13 覆盖式）：FINALIZED→DRAFT 写事件；DRAFT 态宽容 no-op 不写事件（T3 裁定语义照传）。 */
    public StrategyView reviseStrategy(Long userId, Long projectId) {
        ResearchProject project = requireProject(repository, userId, projectId);
        StrategyDoc doc = requireStrategy(projectId);
        StrategyDoc revised = repository.saveStrategy(doc.revise(clock.instant()));
        if (doc.state() == StrategyState.FINALIZED) {
            writeEvent(journalRepository, clock, project, "策略修订", "定稿策略修订回草稿（覆盖式）");
        }
        return StrategyView.from(revised);
    }

    private StrategyDoc requireStrategy(Long projectId) {
        return repository.findStrategy(projectId)
                .orElseThrow(() -> new ResearchException(ResearchErrorCode.NOT_FOUND, "策略文档不存在"));
    }
}
