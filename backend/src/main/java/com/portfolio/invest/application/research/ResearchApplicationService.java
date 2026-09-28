package com.portfolio.invest.application.research;

import com.portfolio.invest.application.research.ResearchViews.FalsifierView;
import com.portfolio.invest.application.research.ResearchViews.ProjectDetailView;
import com.portfolio.invest.application.research.ResearchViews.ProjectView;
import com.portfolio.invest.application.research.ResearchViews.StrategyView;
import com.portfolio.invest.domain.journal.JournalEntry;
import com.portfolio.invest.domain.journal.JournalEntryRepository;
import com.portfolio.invest.domain.journal.JournalEntryType;
import com.portfolio.invest.domain.research.Falsifier;
import com.portfolio.invest.domain.research.FalsifierKind;
import com.portfolio.invest.domain.research.FalsifierPredicate;
import com.portfolio.invest.domain.research.ProjectStatus;
import com.portfolio.invest.domain.research.ResearchErrorCode;
import com.portfolio.invest.domain.research.ResearchException;
import com.portfolio.invest.domain.research.ResearchProject;
import com.portfolio.invest.domain.research.ResearchProjectRepository;
import com.portfolio.invest.domain.research.ResearchStage;
import com.portfolio.invest.domain.research.StageCompletion;
import com.portfolio.invest.domain.research.StageCompletionService;
import com.portfolio.invest.domain.research.StageCompletionService.ManualState;
import com.portfolio.invest.domain.research.StrategyDoc;
import com.portfolio.invest.domain.research.StrategyState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 研究项目用例编排（F05 立项 / S1 归档 / D4 阶段流转 / D13 策略两级状态机 / D10 证伪条件集 / F08 反查支持）。
 *
 * <p>journal 事件写入（S3 跨域编排）：type=RESEARCH_EVENT、title=事件名、projectId 必填、
 * stockCode/stockName 带项目标的；直接走 {@link JournalEntryRepository}（计划裁定：不经
 * {@code JournalApplicationService}，避免 command 面拉宽）。多写操作（项目落库 + 事件）同事务。
 */
@Service
public class ResearchApplicationService {

    /** PATCH 手动标记项：state 取 StageCompletionService.ManualState（NULL=清除覆盖）。 */
    public record ManualMark(@NotNull ResearchStage stage, @NotNull ManualState state) {}

    /** PATCH 项目命令：三组字段均可选，仅提交的字段生效。 */
    public record UpdateProjectCommand(String title, ResearchStage currentStage,
                                       List<@Valid ManualMark> manualMarks) {}

    /** PUT 证伪条件项：kind 决定构造工厂，字段校验由 Falsifier 工厂承担。 */
    public record SaveFalsifierItem(@NotNull FalsifierKind kind, FalsifierPredicate predicate,
                                    BigDecimal threshold, String note) {}

    private final ResearchProjectRepository repository;
    private final JournalEntryRepository journalRepository;

    public ResearchApplicationService(ResearchProjectRepository repository,
                                      JournalEntryRepository journalRepository) {
        this.repository = repository;
        this.journalRepository = journalRepository;
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
    @Transactional
    public ProjectView createProject(Long userId, CreateProjectCommand cmd) {
        ResearchProject project = repository.save(ResearchProject.create(userId, cmd.stockCode(),
                cmd.stockName(), cmd.industryCode(), cmd.title().trim(), ResearchStage.NEW_ANALYSIS));
        writeEvent(project, "立项：" + project.stockName(),
                "研究项目「" + project.title() + "」立项");
        if (Boolean.TRUE.equals(cmd.withTemplate())) {
            writeEvent(project, "模板已带入", "新分析 SOP 模板已带入项目");
        }
        return ProjectView.from(project);
    }

    /** 详情：完成度读模型（S6 唯一计算点）+ 策略（可 null）+ 证伪条件集。 */
    public ProjectDetailView getProject(Long userId, Long projectId) {
        return detailView(requireProject(userId, projectId));
    }

    /**
     * PATCH：改标题 / 切阶段（写「阶段变更」事件，D4 灵活流转任意跳转）/ 手动标记
     * （写 stage_record + 「手动标记」事件，D16 兜底），单事务整组生效。
     */
    @Transactional
    public ProjectDetailView updateProject(Long userId, Long projectId, UpdateProjectCommand cmd) {
        ResearchProject current = requireProject(userId, projectId);
        if (cmd.title() != null) {
            current = repository.save(current.rename(cmd.title().trim()));
        }
        if (cmd.currentStage() != null && cmd.currentStage() != current.currentStage()) {
            current = repository.save(current.changeStage(cmd.currentStage()));
            writeEvent(current, "阶段变更：" + cmd.currentStage().label(),
                    "当前阶段变更为「" + cmd.currentStage().label() + "」");
        }
        if (cmd.manualMarks() != null) {
            for (ManualMark mark : cmd.manualMarks()) {
                repository.saveManualState(projectId, mark.stage(), mark.state());
                writeEvent(current, "手动标记：" + mark.stage().label(),
                        "阶段「" + mark.stage().label() + "」标记为「" + mark.state().label() + "」");
            }
        }
        return detailView(current);
    }

    /** 归档（S1）：幂等语义；实际转入归档时写事件，重复归档不重复写。 */
    @Transactional
    public ProjectView archiveProject(Long userId, Long projectId) {
        ResearchProject project = requireProject(userId, projectId);
        ResearchProject archived = repository.save(project.archive());
        if (project.status() != ProjectStatus.ARCHIVED) {
            writeEvent(archived, "已归档", "研究项目「" + archived.title() + "」已归档");
        }
        return ProjectView.from(archived);
    }

    /** 策略查询：未创建过草稿 → NOT_FOUND（详情视图以 strategy=null 表达同一事实）。 */
    public StrategyView getStrategy(Long userId, Long projectId) {
        requireProject(userId, projectId);
        return StrategyView.from(requireStrategy(projectId));
    }

    /** 暂存草稿（D13）：无文档时以 draftOf 新建；FINALIZED 态拒绝（须先 revise）。 */
    @Transactional
    public StrategyView saveStrategyDraft(Long userId, Long projectId, SaveStrategyCommand cmd) {
        requireProject(userId, projectId);
        StrategyDoc existing = repository.findStrategy(projectId)
                .orElseGet(() -> StrategyDoc.draftOf(projectId));
        StrategyDoc saved = repository.saveStrategy(existing.saveDraft(cmd.thesis(), cmd.valuationLow(),
                cmd.valuationHigh(), cmd.positionPlan(), cmd.buyConditions(), cmd.riskNotes()));
        return StrategyView.from(saved);
    }

    /** 定稿（D13 显式动作）：估值区间校验 domain 先拦（VALUATION_RANGE_INVALID→422）；写「策略定稿」事件。 */
    @Transactional
    public StrategyView finalizeStrategy(Long userId, Long projectId) {
        ResearchProject project = requireProject(userId, projectId);
        StrategyDoc doc = requireStrategy(projectId);
        StrategyDoc finalized = repository.saveStrategy(doc.finalizeDoc());
        if (doc.state() != StrategyState.FINALIZED) {
            writeEvent(project, "策略定稿",
                    "策略文档定稿，估值区间 " + finalized.valuationLow() + " ~ " + finalized.valuationHigh());
        }
        return StrategyView.from(finalized);
    }

    /** 修订（D13 覆盖式）：FINALIZED→DRAFT 写事件；DRAFT 态宽容 no-op 不写事件（T3 裁定语义照传）。 */
    @Transactional
    public StrategyView reviseStrategy(Long userId, Long projectId) {
        ResearchProject project = requireProject(userId, projectId);
        StrategyDoc doc = requireStrategy(projectId);
        StrategyDoc revised = repository.saveStrategy(doc.revise());
        if (doc.state() == StrategyState.FINALIZED) {
            writeEvent(project, "策略修订", "定稿策略修订回草稿（覆盖式）");
        }
        return StrategyView.from(revised);
    }

    /** 证伪条件集查询：未建策略返回空列表（与详情视图 falsifiers=[] 一致）。 */
    public List<FalsifierView> getFalsifiers(Long userId, Long projectId) {
        requireProject(userId, projectId);
        return repository.findStrategy(projectId)
                .map(doc -> repository.findFalsifiers(doc.id()).stream().map(FalsifierView::from).toList())
                .orElse(List.of());
    }

    /** 证伪条件整替保存（D10）：须先有策略文档（STRATEGY_REQUIRED）；字段校验由 Falsifier 工厂承担。 */
    @Transactional
    public List<FalsifierView> saveFalsifiers(Long userId, Long projectId, List<SaveFalsifierItem> items) {
        requireProject(userId, projectId);
        StrategyDoc strategy = repository.findStrategy(projectId)
                .orElseThrow(() -> new ResearchException(ResearchErrorCode.STRATEGY_REQUIRED, "请先保存策略草稿"));
        List<Falsifier> falsifiers = items.stream()
                .map(item -> switch (item.kind()) {
                    case PREDICATE -> Falsifier.ofPredicate(strategy.id(), item.predicate(),
                            item.threshold(), item.note());
                    case EVENT -> Falsifier.ofEvent(strategy.id(), item.note());
                })
                .toList();
        repository.saveFalsifiers(strategy.id(), falsifiers);
        return repository.findFalsifiers(strategy.id()).stream().map(FalsifierView::from).toList();
    }

    /** 归属双重保障（照 journal requireEntry 模式）：非本人/不存在一律 NOT_FOUND，不泄漏存在性。 */
    private ResearchProject requireProject(Long userId, Long projectId) {
        return repository.findById(projectId)
                .filter(p -> Objects.equals(p.userId(), userId))
                .orElseThrow(() -> new ResearchException(ResearchErrorCode.NOT_FOUND, "研究项目不存在"));
    }

    private StrategyDoc requireStrategy(Long projectId) {
        return repository.findStrategy(projectId)
                .orElseThrow(() -> new ResearchException(ResearchErrorCode.NOT_FOUND, "策略文档不存在"));
    }

    private ProjectDetailView detailView(ResearchProject project) {
        Map<ResearchStage, ManualState> manualStates = repository.findManualStates(project.id());
        Optional<StrategyDoc> strategy = repository.findStrategy(project.id());
        List<Falsifier> falsifiers = strategy.map(doc -> repository.findFalsifiers(doc.id())).orElse(List.of());
        Map<ResearchStage, StageCompletion> completions =
                StageCompletionService.compute(project, manualStates, artifactCounts(strategy));
        return ProjectDetailView.of(project, completions, strategy.orElse(null), falsifiers);
    }

    /**
     * 各阶段产物计数（AUTO 完成度输入，S6 简化裁定）：
     * <ul>
     *   <li>NEW_ANALYSIS→1：项目存在+标题即视为 1 件分析记录（StageArtifactCatalog NEW_ANALYSIS→1
     *       的语义 = 立项即有分析 checklist；v1 不在 research 侧复制 checklist 条目）</li>
     *   <li>STRATEGY→定稿即 1（DRAFT 不算产物）；</li>
     *   <li>POSITION/REVIEW→0：建仓计划/检查留痕/复盘产物源由后续里程碑接入（P3/P4）。</li>
     * </ul>
     */
    private Map<ResearchStage, Integer> artifactCounts(Optional<StrategyDoc> strategy) {
        Map<ResearchStage, Integer> counts = new EnumMap<>(ResearchStage.class);
        counts.put(ResearchStage.NEW_ANALYSIS, 1);
        counts.put(ResearchStage.STRATEGY,
                strategy.filter(d -> d.state() == StrategyState.FINALIZED).isPresent() ? 1 : 0);
        counts.put(ResearchStage.POSITION, 0);
        counts.put(ResearchStage.REVIEW, 0);
        return counts;
    }

    /** 研究事件写入（S3）。 */
    private void writeEvent(ResearchProject project, String title, String content) {
        journalRepository.save(JournalEntry.create(project.userId(), JournalEntryType.RESEARCH_EVENT,
                project.stockCode(), project.stockName(), null, title, content,
                null, null, null, null, null, LocalDate.now(), Instant.now(), project.id()));
    }

    private static boolean matches(ResearchProject p, String q) {
        return containsIgnoreCase(p.title(), q) || containsIgnoreCase(p.stockName(), q)
                || containsIgnoreCase(p.stockCode(), q);
    }

    private static boolean containsIgnoreCase(String value, String q) {
        return value != null && value.toLowerCase().contains(q.toLowerCase());
    }
}
