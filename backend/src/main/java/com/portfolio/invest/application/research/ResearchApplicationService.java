package com.portfolio.invest.application.research;

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
import com.portfolio.invest.domain.research.CheckItemResult;
import com.portfolio.invest.domain.research.CheckResult;
import com.portfolio.invest.domain.research.CheckType;
import com.portfolio.invest.domain.research.FalsifierKind;
import com.portfolio.invest.domain.research.FalsifierPredicate;
import com.portfolio.invest.domain.research.FalsifierReviewRepository;
import com.portfolio.invest.domain.research.ResearchCheckRepository;
import com.portfolio.invest.domain.research.ResearchEntryPlanRepository;
import com.portfolio.invest.domain.research.ResearchFeedbackRepository;
import com.portfolio.invest.domain.research.ResearchProjectRepository;
import com.portfolio.invest.domain.research.ReviewConclusion;
import com.portfolio.invest.domain.research.ReviewRepository;
import com.portfolio.invest.domain.research.ReviewTier;
import com.portfolio.invest.domain.research.StageCompletionService.ManualState;
import com.portfolio.invest.domain.research.ProjectStatus;
import com.portfolio.invest.domain.research.ResearchStage;
import com.portfolio.invest.domain.journal.JournalEntryRepository;
import com.portfolio.invest.domain.wiki.WikiEntryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 研究项目用例门面（2026-10-12 按用例族拆分：原 733 行单类承载立项/策略/证伪/建仓/检查/
 * 复盘/反馈 7 组用例，现各组逻辑纯搬移至
 * {@link ResearchProjectApplicationService}（立项/归档/情报提醒）、
 * {@link ResearchStrategyApplicationService}（策略两级状态机）、
 * {@link ResearchFalsifierApplicationService}（证伪条件/命中合并视图/评审）、
 * {@link ResearchEntryPlanApplicationService}（建仓计划）、
 * {@link ResearchCheckApplicationService}（纪律检查）、
 * {@link ResearchReviewApplicationService}（复盘 CRUD/回流）、
 * {@link ResearchFeedbackApplicationService}（模板建议）；
 * 共享归属校验/事件写入见 {@link ResearchServiceSupport}）。
 *
 * <p>本门面保留全部对外契约：web 注入点、方法签名与命令 record 均未变（web/bdd/测试
 * import 不动），方法体为一行委托；<b>@Transactional 留在门面方法上</b>（事务代理以 Spring
 * bean 为边界，子服务为门面手工装配、非 bean）。行为与拆分前完全一致。
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

    /** 市场时区（与 EmailCodeService 同 zone：事件 eventDate 走业务日历而非系统缺省）。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

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

    private final ResearchProjectApplicationService projectService;
    private final ResearchStrategyApplicationService strategyService;
    private final ResearchFalsifierApplicationService falsifierService;
    private final ResearchEntryPlanApplicationService entryPlanService;
    private final ResearchCheckApplicationService checkService;
    private final ResearchReviewApplicationService reviewService;
    private final ResearchFeedbackApplicationService feedbackService;

    /** 主构造器（@Autowired：存在测试专用重载构造器时需显式指定注入入口）。 */
    @Autowired
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
        this(repository, journalRepository, entryPlanRepository, checkRepository,
                falsifierReviewRepository, orchestration, snapshotAssembler, reviewRepository,
                feedbackRepository, reviewComposer, wikiEntryRepository, mapper, Clock.system(ZONE));
    }

    /** 测试构造器：注入时钟（事件/wiki 打点时间确定性断言）。 */
    ResearchApplicationService(ResearchProjectRepository repository,
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
                               ObjectMapper mapper, Clock clock) {
        this.projectService = new ResearchProjectApplicationService(repository, journalRepository,
                entryPlanRepository, checkRepository, reviewRepository, clock);
        this.strategyService = new ResearchStrategyApplicationService(repository, journalRepository, clock);
        this.falsifierService = new ResearchFalsifierApplicationService(repository, journalRepository,
                checkRepository, falsifierReviewRepository, snapshotAssembler, clock);
        this.entryPlanService = new ResearchEntryPlanApplicationService(repository, entryPlanRepository, clock);
        this.checkService = new ResearchCheckApplicationService(repository, journalRepository,
                entryPlanRepository, checkRepository, snapshotAssembler, orchestration, clock);
        this.reviewService = new ResearchReviewApplicationService(repository, journalRepository,
                entryPlanRepository, reviewRepository, reviewComposer, wikiEntryRepository, mapper, clock);
        this.feedbackService = new ResearchFeedbackApplicationService(repository, reviewRepository,
                feedbackRepository, clock);
    }

    // —— 项目用例族（F05/F07/S1/M16-F11）——

    public List<ProjectView> listProjects(Long userId, ResearchStage stage, ProjectStatus status, String q) {
        return projectService.listProjects(userId, stage, status, q);
    }

    @Transactional
    public ProjectView createProject(Long userId, CreateProjectCommand cmd) {
        return projectService.createProject(userId, cmd);
    }

    public ProjectDetailView getProject(Long userId, Long projectId) {
        return projectService.getProject(userId, projectId);
    }

    @Transactional
    public ProjectDetailView updateProject(Long userId, Long projectId, UpdateProjectCommand cmd) {
        return projectService.updateProject(userId, projectId, cmd);
    }

    @Transactional
    public ProjectView archiveProject(Long userId, Long projectId) {
        return projectService.archiveProject(userId, projectId);
    }

    @Transactional
    public ProjectView setIntelligenceAlert(Long userId, Long projectId, SetIntelligenceAlertCommand cmd) {
        return projectService.setIntelligenceAlert(userId, projectId, cmd);
    }

    // —— 策略用例族（D13）——

    public StrategyView getStrategy(Long userId, Long projectId) {
        return strategyService.getStrategy(userId, projectId);
    }

    @Transactional
    public StrategyView saveStrategyDraft(Long userId, Long projectId, SaveStrategyCommand cmd) {
        return strategyService.saveStrategyDraft(userId, projectId, cmd);
    }

    @Transactional
    public StrategyView finalizeStrategy(Long userId, Long projectId) {
        return strategyService.finalizeStrategy(userId, projectId);
    }

    @Transactional
    public StrategyView reviseStrategy(Long userId, Long projectId) {
        return strategyService.reviseStrategy(userId, projectId);
    }

    // —— 证伪用例族（D10/D21/F15）——

    public List<FalsifierView> getFalsifiers(Long userId, Long projectId) {
        return falsifierService.getFalsifiers(userId, projectId);
    }

    @Transactional
    public List<FalsifierView> saveFalsifiers(Long userId, Long projectId, List<SaveFalsifierItem> items) {
        return falsifierService.saveFalsifiers(userId, projectId, items);
    }

    public List<FalsifierHitView> getHits(Long userId, Long projectId) {
        return falsifierService.getHits(userId, projectId);
    }

    public List<FalsifierReviewView> getFalsifierReviews(Long userId, Long projectId) {
        return falsifierService.getFalsifierReviews(userId, projectId);
    }

    @Transactional
    public FalsifierReviewView submitFalsifierReview(Long userId, Long projectId,
                                                     SubmitFalsifierReviewCommand cmd) {
        return falsifierService.submitFalsifierReview(userId, projectId, cmd);
    }

    // —— 建仓计划用例族（F09）——

    public EntryPlanView getEntryPlan(Long userId, Long projectId) {
        return entryPlanService.getEntryPlan(userId, projectId);
    }

    @Transactional
    public EntryPlanView saveEntryPlan(Long userId, Long projectId, SaveEntryPlanCommand cmd) {
        return entryPlanService.saveEntryPlan(userId, projectId, cmd);
    }

    // —— 纪律检查用例族（F10/F12）——

    public List<CheckItemResult> previewCheck(Long userId, Long projectId, PreviewCheckCommand cmd) {
        return checkService.previewCheck(userId, projectId, cmd);
    }

    @Transactional
    public CheckRecordView submitCheck(Long userId, Long projectId, SubmitCheckCommand cmd) {
        return checkService.submitCheck(userId, projectId, cmd);
    }

    public List<CheckRecordView> getChecks(Long userId, Long projectId) {
        return checkService.getChecks(userId, projectId);
    }

    // —— 复盘用例族（F13/F14/F16 回流）——

    public List<ReviewView> getReviews(Long userId, Long projectId) {
        return reviewService.getReviews(userId, projectId);
    }

    @Transactional
    public ReviewView createReview(Long userId, Long projectId, CreateReviewCommand cmd) {
        return reviewService.createReview(userId, projectId, cmd);
    }

    @Transactional
    public ReviewView updateReview(Long userId, Long projectId, Long reviewId, UpdateReviewCommand cmd) {
        return reviewService.updateReview(userId, projectId, reviewId, cmd);
    }

    @Transactional
    public ReviewView refluxReview(Long userId, Long projectId, Long reviewId) {
        return reviewService.refluxReview(userId, projectId, reviewId);
    }

    // —— 反馈用例族（F16）——

    @Transactional
    public FeedbackView submitFeedback(Long userId, Long projectId, SubmitFeedbackCommand cmd) {
        return feedbackService.submitFeedback(userId, projectId, cmd);
    }
}
