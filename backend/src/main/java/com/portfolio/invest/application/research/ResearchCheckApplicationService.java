package com.portfolio.invest.application.research;

import static com.portfolio.invest.application.research.ResearchServiceSupport.requireProject;
import static com.portfolio.invest.application.research.ResearchServiceSupport.writeEvent;

import com.portfolio.invest.application.research.ResearchViews.CheckRecordView;
import com.portfolio.invest.domain.journal.JournalEntryRepository;
import com.portfolio.invest.domain.research.CheckContext;
import com.portfolio.invest.domain.research.CheckItemResult;
import com.portfolio.invest.domain.research.CheckOutcome;
import com.portfolio.invest.domain.research.CheckRecord;
import com.portfolio.invest.domain.research.CheckResult;
import com.portfolio.invest.domain.research.CheckType;
import com.portfolio.invest.domain.research.DisciplineCheckService;
import com.portfolio.invest.domain.research.EntryPlan;
import com.portfolio.invest.domain.research.Falsifier;
import com.portfolio.invest.domain.research.MarketSnapshot;
import com.portfolio.invest.domain.research.ResearchCheckRepository;
import com.portfolio.invest.domain.research.ResearchEntryPlanRepository;
import com.portfolio.invest.domain.research.ResearchProject;
import com.portfolio.invest.domain.research.ResearchProjectRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * 纪律检查用例族（F10·F12，P3-T4：preview 纯读 / submit 留痕 / 留痕列表）。2026-10-12
 * 按用例族拆分自原 {@link ResearchApplicationService}（纯搬移，行为不变）；事务边界在门面
 * 方法上，本类为门面手工装配、非 Spring bean。检查上下文/规则转换委托
 * {@link CheckOrchestration}，快照组装委托 {@link MarketSnapshotAssembler}。
 */
public class ResearchCheckApplicationService {

    private final ResearchProjectRepository repository;
    private final JournalEntryRepository journalRepository;
    private final ResearchEntryPlanRepository entryPlanRepository;
    private final ResearchCheckRepository checkRepository;
    private final MarketSnapshotAssembler snapshotAssembler;
    private final CheckOrchestration orchestration;
    private final Clock clock;

    public ResearchCheckApplicationService(ResearchProjectRepository repository,
                                           JournalEntryRepository journalRepository,
                                           ResearchEntryPlanRepository entryPlanRepository,
                                           ResearchCheckRepository checkRepository,
                                           MarketSnapshotAssembler snapshotAssembler,
                                           CheckOrchestration orchestration,
                                           Clock clock) {
        this.repository = repository;
        this.journalRepository = journalRepository;
        this.entryPlanRepository = entryPlanRepository;
        this.checkRepository = checkRepository;
        this.snapshotAssembler = snapshotAssembler;
        this.orchestration = orchestration;
        this.clock = clock;
    }

    /**
     * 发起检查（preview，纯读不落库）：组装上下文（计划占比 + 持仓聚合 + 快照估值 + F01 勾选 +
     * 证伪条件集）→ {@link DisciplineCheckService} 纯函数 → 命中项列表。软提醒不阻断（D5）。
     */
    public List<CheckItemResult> previewCheck(Long userId, Long projectId,
                                              ResearchApplicationService.PreviewCheckCommand cmd) {
        ResearchProject project = requireProject(repository, userId, projectId);
        EntryPlan plan = entryPlanRepository.findByProjectId(projectId).orElse(null);
        MarketSnapshot snapshot = snapshotAssembler.assemble(project.stockCode());
        CheckContext ctx = orchestration.buildContext(project, CheckOrchestration.planRatio(plan),
                checkedItems(cmd.f01MustItems()), falsifiersOf(projectId), snapshot);
        return DisciplineCheckService.check(ctx, cmd.checkType(), orchestration.enabledRules(userId));
    }

    /**
     * 提交检查留痕（append-only）：CheckRecord.create 域校验（OVERRIDDEN 必填理由→422），
     * 落库 + journal 事件「纪律检查：&lt;类型&gt;/&lt;结论&gt;」同事务。
     */
    public CheckRecordView submitCheck(Long userId, Long projectId,
                                       ResearchApplicationService.SubmitCheckCommand cmd) {
        ResearchProject project = requireProject(repository, userId, projectId);
        CheckRecord record = checkRepository.insert(CheckRecord.create(projectId, cmd.checkType(),
                cmd.items(), cmd.result(), cmd.overrideReason(), clock.instant()));
        long hitCount = cmd.items().stream().filter(item -> item.outcome() == CheckOutcome.HIT).count();
        writeEvent(journalRepository, clock, project, "纪律检查：" + cmd.checkType().label() + "/" + cmd.result().label(),
                "纪律检查「" + cmd.checkType().label() + "」结论「" + cmd.result().label()
                        + "」，命中项 " + hitCount + " 个");
        return CheckRecordView.from(record);
    }

    /** 检查留痕列表（P3-T4 deferred 携带）：createdAt 倒序，复盘 4.3 纪律遵守度预填数据源。 */
    public List<CheckRecordView> getChecks(Long userId, Long projectId) {
        requireProject(repository, userId, projectId);
        return checkRepository.findChecks(projectId).stream().map(CheckRecordView::from).toList();
    }

    /** 项目当前证伪条件集：无策略文档 → 空列表（与详情视图口径一致）。 */
    private List<Falsifier> falsifiersOf(Long projectId) {
        return repository.findStrategy(projectId)
                .map(doc -> repository.findFalsifiers(doc.id()))
                .orElse(List.of());
    }

    /** F01 勾选布尔 → 已确认项集合（true 才进上下文；null 容错）。 */
    private static List<String> checkedItems(Map<String, Boolean> f01MustItems) {
        if (f01MustItems == null) {
            return List.of();
        }
        return f01MustItems.entrySet().stream()
                .filter(entry -> Boolean.TRUE.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
    }
}
