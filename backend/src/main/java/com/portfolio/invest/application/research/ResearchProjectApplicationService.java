package com.portfolio.invest.application.research;

import static com.portfolio.invest.application.research.ResearchServiceSupport.requireProject;
import static com.portfolio.invest.application.research.ResearchServiceSupport.writeEvent;

import com.portfolio.invest.application.research.ResearchViews.ProjectDetailView;
import com.portfolio.invest.application.research.ResearchViews.ProjectView;
import com.portfolio.invest.domain.journal.JournalEntryRepository;
import com.portfolio.invest.domain.research.Falsifier;
import com.portfolio.invest.domain.research.ResearchCheckRepository;
import com.portfolio.invest.domain.research.ResearchEntryPlanRepository;
import com.portfolio.invest.domain.research.ResearchProject;
import com.portfolio.invest.domain.research.ResearchProjectRepository;
import com.portfolio.invest.domain.research.ResearchStage;
import com.portfolio.invest.domain.research.ReviewRepository;
import com.portfolio.invest.domain.research.StageCompletion;
import com.portfolio.invest.domain.research.StageCompletionService;
import com.portfolio.invest.domain.research.StageCompletionService.ManualState;
import com.portfolio.invest.domain.research.StrategyDoc;
import com.portfolio.invest.domain.research.StrategyState;
import com.portfolio.invest.domain.research.ProjectStatus;
import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 研究项目用例族（F05 立项 / F07 列表 / 详情 / PATCH 更新 / S1 归档 / M16-F11 情报提醒开关）。
 *
 * <p>2026-10-12 按用例族拆分自原 {@link ResearchApplicationService}（纯搬移，行为不变）；
 * 事务边界仍在门面方法上（@Transactional 由 Spring 代理生效），本类为门面手工装配、非 Spring bean。
 * 命令 record 留驻门面（web 层 import 的契约类型不变）。
 */
public class ResearchProjectApplicationService {

    private final ResearchProjectRepository repository;
    private final JournalEntryRepository journalRepository;
    private final ResearchEntryPlanRepository entryPlanRepository;
    private final ResearchCheckRepository checkRepository;
    private final ReviewRepository reviewRepository;
    private final Clock clock;

    public ResearchProjectApplicationService(ResearchProjectRepository repository,
                                             JournalEntryRepository journalRepository,
                                             ResearchEntryPlanRepository entryPlanRepository,
                                             ResearchCheckRepository checkRepository,
                                             ReviewRepository reviewRepository,
                                             Clock clock) {
        this.repository = repository;
        this.journalRepository = journalRepository;
        this.entryPlanRepository = entryPlanRepository;
        this.checkRepository = checkRepository;
        this.reviewRepository = reviewRepository;
        this.clock = clock;
    }

    /**
     * 列表（F07）：默认滤 ARCHIVED（显式传 status 可查归档），stage/q 在内存过滤
     * （q 匹配标题/标的名称/标的代码，忽略大小写）；排序由仓库保证（updated_at 倒序）。
     */
    public List<ProjectView> listProjects(Long userId, ResearchStage stage, ProjectStatus status, String q) {
        ProjectStatus effective = status == null ? ProjectStatus.ACTIVE : status;
        return repository.findByUserId(userId, effective).stream()
                .filter(p -> stage == null || p.currentStage() == stage)
                .filter(p -> q == null || q.isBlank() || matches(p, q.trim()))
                .map(ProjectView::from)
                .toList();
    }

    /**
     * 立项（F05）：初始阶段固定 NEW_ANALYSIS，写「立项：&lt;stockName&gt;」事件；
     * withTemplate=true 时追加「模板已带入」事件（SOP 模板内容源 wiki SOP_TEMPLATE，v1 不复制条目）。
     */
    public ProjectView createProject(Long userId, CreateProjectCommand cmd) {
        ResearchProject project = repository.save(ResearchProject.create(userId, cmd.stockCode(),
                cmd.stockName(), cmd.industryCode(), cmd.title().trim(), ResearchStage.NEW_ANALYSIS,
                clock.instant()));
        writeEvent(journalRepository, clock, project, "立项：" + project.stockName(),
                "研究项目「" + project.title() + "」立项");
        if (Boolean.TRUE.equals(cmd.withTemplate())) {
            writeEvent(journalRepository, clock, project, "模板已带入", "新分析 SOP 模板已带入项目");
        }
        return ProjectView.from(project);
    }

    /** 详情：完成度读模型（S6 唯一计算点）+ 策略（可 null）+ 证伪条件集。 */
    public ProjectDetailView getProject(Long userId, Long projectId) {
        return detailView(requireProject(repository, userId, projectId));
    }

    /**
     * PATCH：改标题 / 切阶段（写「阶段变更」事件，D4 灵活流转任意跳转）/ 手动标记
     * （写 stage_record + 「手动标记」事件，D16 兜底），单事务整组生效。
     */
    public ProjectDetailView updateProject(Long userId, Long projectId,
                                           ResearchApplicationService.UpdateProjectCommand cmd) {
        ResearchProject current = requireProject(repository, userId, projectId);
        if (cmd.title() != null) {
            current = repository.save(current.rename(cmd.title().trim(), clock.instant()));
        }
        if (cmd.currentStage() != null && cmd.currentStage() != current.currentStage()) {
            current = repository.save(current.changeStage(cmd.currentStage(), clock.instant()));
            writeEvent(journalRepository, clock, current, "阶段变更：" + cmd.currentStage().label(),
                    "当前阶段变更为「" + cmd.currentStage().label() + "」");
        }
        if (cmd.manualMarks() != null) {
            for (ResearchApplicationService.ManualMark mark : cmd.manualMarks()) {
                repository.saveManualState(projectId, mark.stage(), mark.state());
                writeEvent(journalRepository, clock, current, "手动标记：" + mark.stage().label(),
                        "阶段「" + mark.stage().label() + "」标记为「" + mark.state().label() + "」");
            }
        }
        return detailView(current);
    }

    /** 归档（S1）：幂等语义；实际转入归档时写事件，重复归档不重复写。 */
    public ProjectView archiveProject(Long userId, Long projectId) {
        ResearchProject project = requireProject(repository, userId, projectId);
        ResearchProject archived = repository.save(project.archive(clock.instant()));
        if (project.status() != ProjectStatus.ARCHIVED) {
            writeEvent(journalRepository, clock, archived, "已归档", "研究项目「" + archived.title() + "」已归档");
        }
        return ProjectView.from(archived);
    }

    /**
     * 情报提醒开关（M16-F11 回收，D13/决策 #26）：开关关即该项目从持仓情报挂接
     * （{@code ResearchIntelligenceSubscriptionHookImpl} 查 intelligence_alert_enabled）消失。
     * 不写 journal 事件——订阅行为非研究事件，项目时间线零噪音。
     */
    public ProjectView setIntelligenceAlert(Long userId, Long projectId,
                                            ResearchApplicationService.SetIntelligenceAlertCommand cmd) {
        ResearchProject project = requireProject(repository, userId, projectId);
        return ProjectView.from(repository.save(project.withIntelligenceAlert(cmd.enabled(), clock.instant())));
    }

    private ProjectDetailView detailView(ResearchProject project) {
        Map<ResearchStage, ManualState> manualStates = repository.findManualStates(project.id());
        Optional<StrategyDoc> strategy = repository.findStrategy(project.id());
        List<Falsifier> falsifiers = strategy.map(doc -> repository.findFalsifiers(doc.id())).orElse(List.of());
        Map<ResearchStage, StageCompletion> completions =
                StageCompletionService.compute(project, manualStates, artifactCounts(project.id(), strategy));
        return ProjectDetailView.of(project, completions, strategy.orElse(null), falsifiers);
    }

    /**
     * 各阶段产物计数（AUTO 完成度输入，S6；件数语义对齐 {@link StageArtifactCatalog}）：
     * <ul>
     *   <li>NEW_ANALYSIS→1：项目存在+标题即视为 1 件分析记录（目录 NEW_ANALYSIS→1 的语义 =
     *       立项即有分析 checklist；v1 不在 research 侧复制 checklist 条目）</li>
     *   <li>STRATEGY→定稿即 1（DRAFT 不算产物）；</li>
     *   <li>POSITION→建仓计划存在 1 件 + 检查留痕非空 1 件（目录 2 件，P3 接入）；</li>
     *   <li>REVIEW→复盘记录非空即 1 件（目录 1 件，P4 接入）。</li>
     * </ul>
     * 存在性探查复用既有读端口（findByProjectId/findChecks/findByProjectId），不另开 count 查询。
     */
    private Map<ResearchStage, Integer> artifactCounts(Long projectId, Optional<StrategyDoc> strategy) {
        Map<ResearchStage, Integer> counts = new EnumMap<>(ResearchStage.class);
        counts.put(ResearchStage.NEW_ANALYSIS, 1);
        counts.put(ResearchStage.STRATEGY,
                strategy.filter(d -> d.state() == StrategyState.FINALIZED).isPresent() ? 1 : 0);
        counts.put(ResearchStage.POSITION,
                (entryPlanRepository.findByProjectId(projectId).isPresent() ? 1 : 0)
                        + (checkRepository.findChecks(projectId).isEmpty() ? 0 : 1));
        counts.put(ResearchStage.REVIEW, reviewRepository.findByProjectId(projectId).isEmpty() ? 0 : 1);
        return counts;
    }

    private static boolean matches(ResearchProject p, String q) {
        return containsIgnoreCase(p.title(), q) || containsIgnoreCase(p.stockName(), q)
                || containsIgnoreCase(p.stockCode(), q);
    }

    private static boolean containsIgnoreCase(String value, String q) {
        return value != null && value.toLowerCase().contains(q.toLowerCase());
    }
}
