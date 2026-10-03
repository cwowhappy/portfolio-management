package com.portfolio.invest.application.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.application.research.ResearchViews.CheckRecordView;
import com.portfolio.invest.application.research.ResearchViews.EntryPlanView;
import com.portfolio.invest.application.research.ResearchViews.FalsifierHitView;
import com.portfolio.invest.application.research.ResearchViews.FalsifierReviewView;
import com.portfolio.invest.application.research.ResearchViews.FalsifierView;
import com.portfolio.invest.application.research.ResearchViews.FeedbackView;
import com.portfolio.invest.application.research.ResearchViews.ProjectDetailView;
import com.portfolio.invest.application.research.ResearchViews.ProjectView;
import com.portfolio.invest.application.research.ResearchViews.ReviewView;
import com.portfolio.invest.application.research.ResearchViews.StrategyView;
import com.portfolio.invest.domain.journal.JournalEntry;
import com.portfolio.invest.domain.journal.JournalEntryRepository;
import com.portfolio.invest.domain.journal.JournalEntryType;
import com.portfolio.invest.domain.research.CheckContext;
import com.portfolio.invest.domain.research.CheckItemResult;
import com.portfolio.invest.domain.research.CheckOutcome;
import com.portfolio.invest.domain.research.CheckRecord;
import com.portfolio.invest.domain.research.CheckResult;
import com.portfolio.invest.domain.research.CheckType;
import com.portfolio.invest.domain.research.DisciplineCheckService;
import com.portfolio.invest.domain.research.EntryBatch;
import com.portfolio.invest.domain.research.EntryPlan;
import com.portfolio.invest.domain.research.Falsifier;
import com.portfolio.invest.domain.research.FalsifierEvaluator;
import com.portfolio.invest.domain.research.FalsifierHit;
import com.portfolio.invest.domain.research.FalsifierHitResult;
import com.portfolio.invest.domain.research.FalsifierKind;
import com.portfolio.invest.domain.research.FalsifierPredicate;
import com.portfolio.invest.domain.research.FalsifierReview;
import com.portfolio.invest.domain.research.FalsifierReviewRepository;
import com.portfolio.invest.domain.research.MarketSnapshot;
import com.portfolio.invest.domain.research.ProjectStatus;
import com.portfolio.invest.domain.research.RefluxState;
import com.portfolio.invest.domain.research.ResearchCheckRepository;
import com.portfolio.invest.domain.research.ResearchEntryPlanRepository;
import com.portfolio.invest.domain.research.ResearchErrorCode;
import com.portfolio.invest.domain.research.ResearchException;
import com.portfolio.invest.domain.research.ResearchFeedback;
import com.portfolio.invest.domain.research.ResearchFeedbackRepository;
import com.portfolio.invest.domain.research.ResearchProject;
import com.portfolio.invest.domain.research.ResearchProjectRepository;
import com.portfolio.invest.domain.research.ResearchStage;
import com.portfolio.invest.domain.research.Review;
import com.portfolio.invest.domain.research.ReviewConclusion;
import com.portfolio.invest.domain.research.ReviewRepository;
import com.portfolio.invest.domain.research.ReviewTier;
import com.portfolio.invest.domain.research.StageCompletion;
import com.portfolio.invest.domain.research.StageCompletionService;
import com.portfolio.invest.domain.research.StageCompletionService.ManualState;
import com.portfolio.invest.domain.research.StrategyDoc;
import com.portfolio.invest.domain.research.StrategyState;
import com.portfolio.invest.domain.wiki.WikiEntry;
import com.portfolio.invest.domain.wiki.WikiEntryRepository;
import com.portfolio.invest.domain.wiki.WikiEntryType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 研究项目用例编排（F05 立项 / S1 归档 / D4 阶段流转 / D13 策略两级状态机 / D10 证伪条件集 /
 * F09 建仓计划 / F10·F12 纪律检查 / D21 证伪命中合并视图 / F15 证伪评审 /
 * F13·F14 复盘 CRUD·定格快照 / F16 wiki 回流·模板建议）。
 *
 * <p>journal 事件写入（S3 跨域编排）：type=RESEARCH_EVENT、title=事件名、projectId 必填、
 * stockCode/stockName 带项目标的；直接走 {@link JournalEntryRepository}（计划裁定：不经
 * {@code JournalApplicationService}，避免 command 面拉宽）。多写操作（项目落库 + 事件）同事务。
 *
 * <p>P3-T4 追加：检查 preview（纯读不落库）与 submit（append-only 留痕 + 事件同事务）分离；
 * 检查上下文/规则转换/快照组装委托 {@link CheckOrchestration} 与 {@link MarketSnapshotAssembler}。
 */
@Service
public class ResearchApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ResearchApplicationService.class);

    /** 回流 wiki 条目 category 标记（F16：复用 RESEARCH_NOTE 三类型不扩枚举，S2 与 SOP_TEMPLATE 同法）。 */
    static final String SOP_REVIEW_CATEGORY = "SOP_REVIEW";

    /** PATCH 手动标记项：state 取 StageCompletionService.ManualState（NULL=清除覆盖）。 */
    public record ManualMark(@NotNull ResearchStage stage, @NotNull ManualState state) {}

    /** PATCH 项目命令：三组字段均可选，仅提交的字段生效。 */
    public record UpdateProjectCommand(String title, ResearchStage currentStage,
                                       List<@Valid ManualMark> manualMarks) {}

    /** PUT 情报提醒开关命令（M16-F11 回收，D13/决策 #26）：enabled 必填（缺失 → 400）。 */
    public record SetIntelligenceAlertCommand(@NotNull Boolean enabled) {}

    /**
     * PUT 证伪条件项：kind 决定构造工厂，字段校验由 Falsifier 工厂承担；
     * eventChecked 仅 EVENT 消费（人工勾选「已确认事件」置位的唯一写路径），PREDICATE 忽略。
     */
    public record SaveFalsifierItem(@NotNull FalsifierKind kind, FalsifierPredicate predicate,
                                    BigDecimal threshold, Boolean eventChecked, String note) {}

    /** PUT 建仓批次项：字段域校验由 EntryBatch 紧凑构造器承担（400 BATCH_INVALID）。 */
    public record SaveEntryBatchItem(@NotNull Integer seq, @NotNull BigDecimal priceLow,
                                     @NotNull BigDecimal priceHigh, @NotNull Long quantity,
                                     BigDecimal amount, @NotNull BigDecimal ratio) {}

    /** PUT 建仓计划命令（整替：每次 of() 重建，无 wither——T1 裁定）。 */
    public record SaveEntryPlanCommand(BigDecimal winRate, BigDecimal payoffRatio,
                                       List<@Valid SaveEntryBatchItem> batches) {}

    /** 发起检查命令：f01MustItems 为 F01 必查项勾选布尔（true=已确认，键与前端确认卡逐字对齐）。 */
    public record PreviewCheckCommand(@NotNull CheckType checkType, Map<String, Boolean> f01MustItems) {}

    /** 提交检查命令：items 为 preview 返回的检查项快照（留痕定格；空列表域拒绝 CHECK_ITEMS_REQUIRED）。 */
    public record SubmitCheckCommand(@NotNull CheckType checkType, @NotNull CheckResult result,
                                     String overrideReason, @NotNull List<CheckItemResult> items) {}

    /** 提交证伪评审命令（F15）：hitId 可空（EVENT 类/综合评审可无具体命中行）；reason 由域校验必填。 */
    public record SubmitFalsifierReviewCommand(Long hitId, @NotNull ReviewConclusion conclusion, String reason) {}

    /** POST 复盘命令（F13/F14）：创建即由服务端组装定格快照 + 自动圈选，不收快照/圈选入参。 */
    public record CreateReviewCommand(@NotNull ReviewTier tier, @NotNull LocalDate periodStart,
                                      @NotNull LocalDate periodEnd) {}

    /** PUT 复盘修正命令（整替语义，F14/D11）：answers 必填（域 422）；overrides/narrative/tradeIds 可选（null=清除）。 */
    public record UpdateReviewCommand(JsonNode answers, JsonNode overrides, String narrative,
                                      List<Long> tradeIds) {}

    /** POST 模板改进建议命令（F16 只收集）：content 必填（域 422），reviewId 为来源复盘可空。 */
    public record SubmitFeedbackCommand(Long reviewId, @NotNull ResearchStage stage, String content) {}

    private final ResearchProjectRepository repository;
    private final JournalEntryRepository journalRepository;
    private final ResearchEntryPlanRepository entryPlanRepository;
    private final ResearchCheckRepository checkRepository;
    private final FalsifierReviewRepository falsifierReviewRepository;
    private final CheckOrchestration orchestration;
    private final MarketSnapshotAssembler snapshotAssembler;
    private final ReviewRepository reviewRepository;
    private final ResearchFeedbackRepository feedbackRepository;
    private final ReviewSnapshotComposer reviewComposer;
    private final WikiEntryRepository wikiEntryRepository;
    private final ObjectMapper mapper;

    public ResearchApplicationService(ResearchProjectRepository repository,
                                      JournalEntryRepository journalRepository,
                                      ResearchEntryPlanRepository entryPlanRepository,
                                      ResearchCheckRepository checkRepository,
                                      FalsifierReviewRepository falsifierReviewRepository,
                                      CheckOrchestration orchestration,
                                      MarketSnapshotAssembler snapshotAssembler,
                                      ReviewRepository reviewRepository,
                                      ResearchFeedbackRepository feedbackRepository,
                                      ReviewSnapshotComposer reviewComposer,
                                      WikiEntryRepository wikiEntryRepository,
                                      ObjectMapper mapper) {
        this.repository = repository;
        this.journalRepository = journalRepository;
        this.entryPlanRepository = entryPlanRepository;
        this.checkRepository = checkRepository;
        this.falsifierReviewRepository = falsifierReviewRepository;
        this.orchestration = orchestration;
        this.snapshotAssembler = snapshotAssembler;
        this.reviewRepository = reviewRepository;
        this.feedbackRepository = feedbackRepository;
        this.reviewComposer = reviewComposer;
        this.wikiEntryRepository = wikiEntryRepository;
        this.mapper = mapper;
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

    /**
     * 情报提醒开关（M16-F11 回收，D13/决策 #26）：开关关即该项目从持仓情报挂接
     * （{@code ResearchIntelligenceSubscriptionHookImpl} 查 intelligence_alert_enabled）消失。
     * 不写 journal 事件——订阅行为非研究事件，项目时间线零噪音。
     */
    @Transactional
    public ProjectView setIntelligenceAlert(Long userId, Long projectId, SetIntelligenceAlertCommand cmd) {
        ResearchProject project = requireProject(userId, projectId);
        return ProjectView.from(repository.save(project.withIntelligenceAlert(cmd.enabled())));
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
                    case EVENT -> Falsifier.ofEvent(strategy.id(), item.note(),
                            Boolean.TRUE.equals(item.eventChecked()));
                })
                .toList();
        repository.saveFalsifiers(strategy.id(), falsifiers);
        return repository.findFalsifiers(strategy.id()).stream().map(FalsifierView::from).toList();
    }

    // —— 建仓计划（F09，P3-T4）——

    /** 建仓计划查询：未保存 → NOT_FOUND（照 getStrategy 先例）。 */
    public EntryPlanView getEntryPlan(Long userId, Long projectId) {
        requireProject(userId, projectId);
        return EntryPlanView.from(requireEntryPlan(projectId));
    }

    /**
     * 建仓计划整替保存：每次 {@link EntryPlan#of} 重建（T1 裁定无 wither），plan+batches 同事务整替；
     * Σratio &gt; 1 唯一硬拒绝（RATIO_SUM_EXCEEDED→422，D5），kellyRatio 读时计算不落库。
     */
    @Transactional
    public EntryPlanView saveEntryPlan(Long userId, Long projectId, SaveEntryPlanCommand cmd) {
        requireProject(userId, projectId);
        EntryPlan plan = EntryPlan.of(projectId, cmd.winRate(), cmd.payoffRatio(), toBatches(cmd.batches()));
        return EntryPlanView.from(entryPlanRepository.save(plan));
    }

    // —— 纪律检查（F10/F12，P3-T4）——

    /**
     * 发起检查（preview，纯读不落库）：组装上下文（计划占比 + 持仓聚合 + 快照估值 + F01 勾选 +
     * 证伪条件集）→ {@link DisciplineCheckService} 纯函数 → 命中项列表。软提醒不阻断（D5）。
     */
    public List<CheckItemResult> previewCheck(Long userId, Long projectId, PreviewCheckCommand cmd) {
        ResearchProject project = requireProject(userId, projectId);
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
    @Transactional
    public CheckRecordView submitCheck(Long userId, Long projectId, SubmitCheckCommand cmd) {
        ResearchProject project = requireProject(userId, projectId);
        CheckRecord record = checkRepository.insert(CheckRecord.create(projectId, cmd.checkType(),
                cmd.items(), cmd.result(), cmd.overrideReason()));
        long hitCount = cmd.items().stream().filter(item -> item.outcome() == CheckOutcome.HIT).count();
        writeEvent(project, "纪律检查：" + cmd.checkType().label() + "/" + cmd.result().label(),
                "纪律检查「" + cmd.checkType().label() + "」结论「" + cmd.result().label()
                        + "」，命中项 " + hitCount + " 个");
        return CheckRecordView.from(record);
    }

    // —— 证伪命中合并视图（D21，P3-T4）——

    /**
     * 证伪命中合并视图：实时求值（FalsifierEvaluator 纯函数，不落库）+ 历史 hit 留痕行。
     * EVENT 条目按勾选状态呈现（Ruling-18：已确认事件/待人工勾选）；历史行仅 PREDICATE 命中
     * （Ruling-18 落表口径，T5 日终扫描同口径）。
     */
    public List<FalsifierHitView> getHits(Long userId, Long projectId) {
        ResearchProject project = requireProject(userId, projectId);
        List<Falsifier> falsifiers = falsifiersOf(projectId);
        List<FalsifierHitView> views = new ArrayList<>();
        if (!falsifiers.isEmpty()) {
            MarketSnapshot snapshot = snapshotAssembler.assemble(project.stockCode());
            for (FalsifierHitResult result : FalsifierEvaluator.evaluate(falsifiers, snapshot)) {
                views.add(realtimeView(result));
            }
        }
        Map<Long, Falsifier> byId = falsifiers.stream()
                .collect(Collectors.toMap(Falsifier::id, Function.identity(), (a, b) -> a));
        for (FalsifierHit hit : checkRepository.findHits(projectId)) {
            views.add(historyView(hit, byId));
        }
        return views;
    }

    // —— 证伪评审（F15，P4-T2）——

    /** 评审留痕列表（前端列表用）：createdAt 倒序；suggestStrategyRevise 由结论推导。 */
    public List<FalsifierReviewView> getFalsifierReviews(Long userId, Long projectId) {
        requireProject(userId, projectId);
        return falsifierReviewRepository.findReviews(projectId).stream()
                .map(review -> FalsifierReviewView.of(review, null))
                .toList();
    }

    /**
     * 提交证伪评审（F15）：append-only 落库 + hit 回填 reviewId + journal 事件
     * 「证伪评审：&lt;结论&gt;」同事务；hitId 须为本项目命中行（否则 NOT_FOUND，不落库）。
     * REVISE 只置 suggestStrategyRevise 提示位、<b>不自动改策略状态</b>（Review Focus 3：
     * 落库与策略修订分离，避免隐式派生——由前端引导用户显式 revise）。
     */
    @Transactional
    public FalsifierReviewView submitFalsifierReview(Long userId, Long projectId,
                                                     SubmitFalsifierReviewCommand cmd) {
        ResearchProject project = requireProject(userId, projectId);
        // 域校验（结论/理由）先于 hit 资源解析：请求体非法 → 422，与 hitId 无关且零写库
        FalsifierReview review = FalsifierReview.create(projectId, cmd.conclusion(), cmd.reason());
        if (cmd.hitId() != null) {
            checkRepository.findHit(projectId, cmd.hitId())
                    .orElseThrow(() -> new ResearchException(ResearchErrorCode.NOT_FOUND, "证伪命中留痕不存在"));
        }
        FalsifierReview inserted = falsifierReviewRepository.insert(review);
        if (cmd.hitId() != null) {
            falsifierReviewRepository.attachReview(cmd.hitId(), inserted.id());
        }
        writeEvent(project, "证伪评审：" + cmd.conclusion().label(),
                "证伪命中评审结论「" + cmd.conclusion().label() + "」，理由：" + cmd.reason());
        return FalsifierReviewView.of(inserted, cmd.hitId());
    }

    // —— 复盘 CRUD / 回流 / 建议 / 检查留痕读取（F13/F14/F16，P4-T3）——

    /** 复盘列表：仓库 periodStart 倒序透传；快照原样回显（读路径不复算，F14 定格）。 */
    public List<ReviewView> getReviews(Long userId, Long projectId) {
        requireProject(userId, projectId);
        return reviewRepository.findByProjectId(projectId).stream().map(ReviewView::from).toList();
    }

    /**
     * 创建复盘（创建即定格）：{@link ReviewSnapshotComposer} 组装带口径标注的快照 JSONB
     * （区间收益/后续走势/归因窗口，写入后无任何重算入口）+ 从快照解析自动圈选 trade_ids
     * （D11 时间窗口径留痕于快照，软引用列此后可经 PUT 手动修正）。
     * F08 时间线：创建即写「复盘创建：&lt;档位&gt;」事件（与回流事件同入项目时间线）。
     */
    @Transactional
    public ReviewView createReview(Long userId, Long projectId, CreateReviewCommand cmd) {
        ResearchProject project = requireProject(userId, projectId);
        List<EntryBatch> batches = entryPlanRepository.findByProjectId(projectId)
                .map(EntryPlan::batches)
                .orElse(List.of());
        String snapshot = reviewComposer.compose(userId, project, cmd.periodStart(), cmd.periodEnd(), batches);
        Review saved = reviewRepository.save(Review.create(projectId, cmd.tier(),
                cmd.periodStart(), cmd.periodEnd(), snapshot, autoCircle(snapshot)));
        writeEvent(project, "复盘创建：" + cmd.tier().label(),
                cmd.tier().label() + " " + cmd.periodStart() + "~" + cmd.periodEnd() + " 创建，快照已定格");
        return ReviewView.from(saved);
    }

    /**
     * 修正复盘（PUT 整替）：answers/overrides/narrative/trade_ids 整组替换（域内去重排序收敛，
     * Review Focus 5）；快照与回流状态无变更入口（F14「不复算历史」）。REFLOWN 后仍可修正
     * 叙述，已建 wiki 条目不回写（宽容口径）。
     */
    @Transactional
    public ReviewView updateReview(Long userId, Long projectId, Long reviewId, UpdateReviewCommand cmd) {
        requireProject(userId, projectId);
        Review existing = requireReview(projectId, reviewId);
        Review saved = reviewRepository.save(existing.correct(toJson(cmd.answers()), toJson(cmd.overrides()),
                cmd.narrative(), cmd.tradeIds()));
        return ReviewView.from(saved);
    }

    /**
     * 确认回流（F16 用户确认后入库，不自动）：建 wiki RESEARCH_NOTE（title=复盘·项目·期间、
     * category=SOP_REVIEW、projectId 软引用、内容=复盘叙述——经 {@link WikiEntryRepository}
     * 直建照 journal writeEvent 计划裁定：不经 WikiApplicationService，避免 command 面拉宽）
     * → {@code refluxConfirm}。REFLOWN 态幂等直接返回既有条目（Review Focus 4）；
     * wiki 写异常降级抛 {@code REFLUX_WIKI_UNAVAILABLE}（502）——本事务尚未写复盘行，
     * 既有行保持 PENDING、不阻断复盘保存，可重试。
     */
    @Transactional
    public ReviewView refluxReview(Long userId, Long projectId, Long reviewId) {
        ResearchProject project = requireProject(userId, projectId);
        Review review = requireReview(projectId, reviewId);
        if (review.refluxState() == RefluxState.REFLOWN) {
            return ReviewView.from(review);
        }
        if (review.narrative() == null || review.narrative().isBlank()) {
            throw new ResearchException(ResearchErrorCode.REFLUX_NARRATIVE_REQUIRED,
                    "回流前请先填写复盘叙述（将写入知识库条目内容）");
        }
        WikiEntry entry;
        try {
            entry = wikiEntryRepository.save(WikiEntry.create(userId, WikiEntryType.RESEARCH_NOTE,
                    "复盘·" + project.title() + "·" + review.periodStart() + "~" + review.periodEnd(),
                    review.narrative(), SOP_REVIEW_CATEGORY, null, Instant.now(), projectId));
        } catch (RuntimeException e) {
            // 降级根因留痕（ResearchException 无 cause 构造器，异常经日志链接——照 UserAdminApplicationService 先例）
            log.warn("回流 wiki 写入失败（projectId={}，reviewId={}），降级保持 PENDING", projectId, reviewId, e);
            throw new ResearchException(ResearchErrorCode.REFLUX_WIKI_UNAVAILABLE,
                    "知识库写入失败，复盘已保留，请稍后重试回流");
        }
        Review confirmed = reviewRepository.save(review.refluxConfirm(entry.id()));
        // F08 时间线：回流成功才写事件（幂等早退/降级路径不写）
        writeEvent(project, "复盘回流知识库", "复盘叙述已写入知识库条目「" + entry.title() + "」");
        return ReviewView.from(confirmed);
    }

    /**
     * 模板改进建议（F16 只收集不生效，v1 不做模板自动改写）：append-only 落库，无事件无回流副作用；
     * 域校验先于 review 资源解析（照 T2 先例：请求体非法 → 422 且零写库，reviewId 越项目 → 404）。
     */
    @Transactional
    public FeedbackView submitFeedback(Long userId, Long projectId, SubmitFeedbackCommand cmd) {
        requireProject(userId, projectId);
        ResearchFeedback feedback = ResearchFeedback.create(projectId, cmd.reviewId(), cmd.stage(), cmd.content());
        if (cmd.reviewId() != null) {
            requireReview(projectId, cmd.reviewId());
        }
        return FeedbackView.from(feedbackRepository.insert(feedback));
    }

    /** 检查留痕列表（P3-T4 deferred 携带）：createdAt 倒序，复盘 4.3 纪律遵守度预填数据源。 */
    public List<CheckRecordView> getChecks(Long userId, Long projectId) {
        requireProject(userId, projectId);
        return checkRepository.findChecks(projectId).stream().map(CheckRecordView::from).toList();
    }

    /** 自动圈选（D11）：从 Composer 定格快照解析 tradeIds（缺键/非数组容忍为空；去重排序收敛由域承担）。 */
    private List<Long> autoCircle(String snapshotJson) {
        JsonNode ids;
        try {
            ids = mapper.readTree(snapshotJson).path("tradeIds");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("复盘快照解析失败", e); // Composer 产物为合法 JSON，防御性兜底
        }
        if (!ids.isArray()) {
            return List.of();
        }
        List<Long> out = new ArrayList<>(ids.size());
        for (JsonNode id : ids) {
            out.add(id.asLong());
        }
        return out;
    }

    /** wire JsonNode → 域 JSON 字符串（null/JSON null 归一为 null，未作答语义）。 */
    private String toJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("复盘 JSON 字段序列化失败", e);
        }
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

    /** 项目域内复盘行读取（照 findHit 口径：越项目/不存在一律 NOT_FOUND，不泄漏存在性）。 */
    private Review requireReview(Long projectId, Long reviewId) {
        return reviewRepository.findByIdAndProjectId(reviewId, projectId)
                .orElseThrow(() -> new ResearchException(ResearchErrorCode.NOT_FOUND, "复盘不存在"));
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

    /** 研究事件写入（S3）。 */
    private void writeEvent(ResearchProject project, String title, String content) {
        journalRepository.save(JournalEntry.create(project.userId(), JournalEntryType.RESEARCH_EVENT,
                project.stockCode(), project.stockName(), null, title, content,
                null, null, null, null, null, LocalDate.now(), Instant.now(), project.id()));
    }

    private EntryPlan requireEntryPlan(Long projectId) {
        return entryPlanRepository.findByProjectId(projectId)
                .orElseThrow(() -> new ResearchException(ResearchErrorCode.NOT_FOUND, "建仓计划不存在"));
    }

    /** 项目当前证伪条件集：无策略文档 → 空列表（与详情视图口径一致）。 */
    private List<Falsifier> falsifiersOf(Long projectId) {
        return repository.findStrategy(projectId)
                .map(doc -> repository.findFalsifiers(doc.id()))
                .orElse(List.of());
    }

    /** wire 批次项 → 领域批次（T1 deferred 守卫：null 列表视同空、null 元素显式拒绝，均不落库）。 */
    private static List<EntryBatch> toBatches(List<SaveEntryBatchItem> items) {
        if (items == null) {
            return List.of();
        }
        List<EntryBatch> batches = new ArrayList<>(items.size());
        for (SaveEntryBatchItem item : items) {
            if (item == null) {
                throw new ResearchException(ResearchErrorCode.BATCH_INVALID, "批次条目不能为空");
            }
            batches.add(new EntryBatch(item.seq(), item.priceLow(), item.priceHigh(),
                    item.quantity(), item.amount(), item.ratio()));
        }
        return batches;
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

    /** 实时求值行：EVENT 条目按勾选状态改写 basis/pending（Ruling-18，evaluator 不消费 eventChecked）。 */
    private static FalsifierHitView realtimeView(FalsifierHitResult result) {
        Falsifier falsifier = result.falsifier();
        if (falsifier.kind() == FalsifierKind.EVENT) {
            boolean checked = falsifier.eventChecked();
            return new FalsifierHitView(null, falsifier.id(), falsifier.kind(), null, null,
                    falsifier.note(), checked, false, !checked, false,
                    checked ? "已确认事件" : "待人工勾选", true, null);
        }
        return new FalsifierHitView(null, falsifier.id(), falsifier.kind(), falsifier.predicate(),
                falsifier.threshold(), falsifier.note(), falsifier.eventChecked(),
                result.hit(), result.pending(), result.skipped(), result.basis(), true, null);
    }

    /** 历史留痕行：falsifier 现态回连（条件已删则现态字段 null，仅保 basis/时间）。 */
    private static FalsifierHitView historyView(FalsifierHit hit, Map<Long, Falsifier> byId) {
        Falsifier falsifier = byId.get(hit.falsifierId());
        return new FalsifierHitView(hit.id(), hit.falsifierId(),
                falsifier == null ? null : falsifier.kind(),
                falsifier == null ? null : falsifier.predicate(),
                falsifier == null ? null : falsifier.threshold(),
                falsifier == null ? null : falsifier.note(),
                falsifier != null && falsifier.eventChecked(),
                false, false, false, hit.basis(), false, hit.createdAt());
    }

    private static boolean matches(ResearchProject p, String q) {
        return containsIgnoreCase(p.title(), q) || containsIgnoreCase(p.stockName(), q)
                || containsIgnoreCase(p.stockCode(), q);
    }

    private static boolean containsIgnoreCase(String value, String q) {
        return value != null && value.toLowerCase().contains(q.toLowerCase());
    }
}
