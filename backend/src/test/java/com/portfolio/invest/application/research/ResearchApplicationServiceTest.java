package com.portfolio.invest.application.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.application.research.ResearchApplicationService.CreateReviewCommand;
import com.portfolio.invest.application.research.ResearchApplicationService.ManualMark;
import com.portfolio.invest.application.research.ResearchApplicationService.PreviewCheckCommand;
import com.portfolio.invest.application.research.ResearchApplicationService.SaveEntryBatchItem;
import com.portfolio.invest.application.research.ResearchApplicationService.SaveEntryPlanCommand;
import com.portfolio.invest.application.research.ResearchApplicationService.SaveFalsifierItem;
import com.portfolio.invest.application.research.ResearchApplicationService.SubmitCheckCommand;
import com.portfolio.invest.application.research.ResearchApplicationService.SubmitFeedbackCommand;
import com.portfolio.invest.application.research.ResearchApplicationService.SubmitFalsifierReviewCommand;
import com.portfolio.invest.application.research.ResearchApplicationService.UpdateProjectCommand;
import com.portfolio.invest.application.research.ResearchApplicationService.UpdateReviewCommand;
import com.portfolio.invest.domain.journal.JournalEntry;
import com.portfolio.invest.domain.journal.JournalEntryRepository;
import com.portfolio.invest.domain.journal.JournalEntryType;
import com.portfolio.invest.domain.research.CheckContext;
import com.portfolio.invest.domain.research.CheckItemResult;
import com.portfolio.invest.domain.research.CheckOutcome;
import com.portfolio.invest.domain.research.CheckRecord;
import com.portfolio.invest.domain.research.CheckResult;
import com.portfolio.invest.domain.research.CheckType;
import com.portfolio.invest.domain.research.EntryBatch;
import com.portfolio.invest.domain.research.EntryPlan;
import com.portfolio.invest.domain.research.Falsifier;
import com.portfolio.invest.domain.research.FalsifierHit;
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
import com.portfolio.invest.domain.research.RuleInput;
import com.portfolio.invest.domain.research.StageCompletionService.ManualState;
import com.portfolio.invest.domain.research.StageStatus;
import com.portfolio.invest.domain.research.StrategyDoc;
import com.portfolio.invest.domain.research.StrategyState;
import com.portfolio.invest.domain.wiki.WikiEntry;
import com.portfolio.invest.domain.wiki.WikiEntryRepository;
import com.portfolio.invest.domain.wiki.WikiEntryType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ResearchApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-28T08:00:00Z");

    private final ResearchProjectRepository repository = mock(ResearchProjectRepository.class);
    private final JournalEntryRepository journalRepository = mock(JournalEntryRepository.class);
    private final ResearchEntryPlanRepository entryPlanRepository = mock(ResearchEntryPlanRepository.class);
    private final ResearchCheckRepository checkRepository = mock(ResearchCheckRepository.class);
    private final FalsifierReviewRepository falsifierReviewRepository = mock(FalsifierReviewRepository.class);
    private final CheckOrchestration orchestration = mock(CheckOrchestration.class);
    private final MarketSnapshotAssembler snapshotAssembler = mock(MarketSnapshotAssembler.class);
    private final ReviewRepository reviewRepository = mock(ReviewRepository.class);
    private final ResearchFeedbackRepository feedbackRepository = mock(ResearchFeedbackRepository.class);
    private final ReviewSnapshotComposer reviewComposer = mock(ReviewSnapshotComposer.class);
    private final WikiEntryRepository wikiEntryRepository = mock(WikiEntryRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private ResearchApplicationService service;

    @BeforeEach
    void setUp() {
        service = new ResearchApplicationService(repository, journalRepository, entryPlanRepository,
                checkRepository, falsifierReviewRepository, orchestration, snapshotAssembler,
                reviewRepository, feedbackRepository, reviewComposer, wikiEntryRepository, mapper);
    }

    private static ResearchProject project(Long id, Long userId, ResearchStage stage, ProjectStatus status) {
        return ResearchProject.reconstitute(id, userId, "600519", "贵州茅台", "801120",
                "茅台扩产研究", stage, status, 0L, NOW, NOW);
    }

    private static StrategyDoc strategy(StrategyState state, String low, String high) {
        return StrategyDoc.reconstitute(7L, 5L, state, "扩产逻辑",
                low == null ? null : new BigDecimal(low), high == null ? null : new BigDecimal(high),
                "两成仓", "回踩买入", "需求不及预期", null, 0L, NOW, NOW);
    }

    // —— 立项 ——

    @DisplayName("立项成功：保存项目 + 写一条 RESEARCH_EVENT（title=立项：标的名）")
    @Test
    void givenValidCommand_whenCreate_thenProjectSavedAndSingleEventWritten() {
        when(repository.save(any(ResearchProject.class)))
                .thenAnswer(inv -> project(5L, 1L, ResearchStage.NEW_ANALYSIS, ProjectStatus.ACTIVE));

        var view = service.createProject(1L,
                new CreateProjectCommand("600519", "贵州茅台", "801120", "茅台扩产研究", null));

        assertThat(view.id()).isEqualTo(5L);
        assertThat(view.currentStage()).isEqualTo(ResearchStage.NEW_ANALYSIS);
        assertThat(view.status()).isEqualTo(ProjectStatus.ACTIVE);
        verify(journalRepository, times(1)).save(any(JournalEntry.class));

        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalRepository).save(captor.capture());
        JournalEntry event = captor.getValue();
        assertThat(event.type()).isEqualTo(JournalEntryType.RESEARCH_EVENT);
        assertThat(event.projectId()).isEqualTo(5L);
        assertThat(event.title()).isEqualTo("立项：贵州茅台");
        assertThat(event.tradeId()).isNull();
        assertThat(event.eventDate()).isEqualTo(LocalDate.now());
    }

    @DisplayName("withTemplate 立项：额外写一条「模板已带入」事件")
    @Test
    void givenWithTemplate_whenCreate_thenTemplateEventAlsoWritten() {
        when(repository.save(any(ResearchProject.class)))
                .thenAnswer(inv -> project(5L, 1L, ResearchStage.NEW_ANALYSIS, ProjectStatus.ACTIVE));

        service.createProject(1L,
                new CreateProjectCommand("600519", "贵州茅台", null, "茅台扩产研究", Boolean.TRUE));

        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(JournalEntry::title)
                .containsExactly("立项：贵州茅台", "模板已带入");
    }

    // —— 归属隔离（requireProject 照 journal requireEntry 模式）——

    @DisplayName("非本人项目访问 → NOT_FOUND（不泄漏存在性）")
    @Test
    void givenOthersProject_whenGet_thenNotFound() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 2L,
                ResearchStage.NEW_ANALYSIS, ProjectStatus.ACTIVE)));

        assertThatThrownBy(() -> service.getProject(1L, 5L))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.archiveProject(1L, 5L))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.NOT_FOUND));
    }

    @DisplayName("项目不存在 → NOT_FOUND")
    @Test
    void givenMissingProject_whenGet_thenNotFound() {
        when(repository.findById(404L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getProject(1L, 404L))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.NOT_FOUND));
    }

    // —— 归档 ——

    @DisplayName("归档：置 ARCHIVED + 写事件；再归档幂等不重复写事件")
    @Test
    void givenActiveProject_whenArchive_thenArchivedWithEventAndIdempotent() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.REVIEW, ProjectStatus.ACTIVE)));
        when(repository.save(any(ResearchProject.class)))
                .thenAnswer(inv -> project(5L, 1L, ResearchStage.REVIEW, ProjectStatus.ARCHIVED));

        var view = service.archiveProject(1L, 5L);
        assertThat(view.status()).isEqualTo(ProjectStatus.ARCHIVED);
        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().title()).isEqualTo("已归档");

        // 已归档再归档：幂等返回，不重复写事件
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.REVIEW, ProjectStatus.ARCHIVED)));
        var again = service.archiveProject(1L, 5L);
        assertThat(again.status()).isEqualTo(ProjectStatus.ARCHIVED);
        verify(journalRepository, times(1)).save(any(JournalEntry.class));
    }

    // —— PATCH：标题 / 阶段 / 手动标记 ——

    @DisplayName("PATCH manualMarks：写 stage_record + 写事件，返回含手动完成度的详情")
    @Test
    void givenManualMarks_whenPatch_thenStageRecordUpdatedAndEventWritten() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.NEW_ANALYSIS, ProjectStatus.ACTIVE)));
        when(repository.findManualStates(5L)).thenReturn(Map.of(ResearchStage.REVIEW, ManualState.COMPLETED));
        when(repository.findStrategy(5L)).thenReturn(Optional.empty());

        var detail = service.updateProject(1L, 5L,
                new UpdateProjectCommand(null, null,
                        List.of(new ManualMark(ResearchStage.REVIEW, ManualState.COMPLETED))));

        verify(repository).saveManualState(5L, ResearchStage.REVIEW, ManualState.COMPLETED);
        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().title()).isEqualTo("手动标记：复盘");

        assertThat(detail.completions().get(ResearchStage.REVIEW).status()).isEqualTo(StageStatus.COMPLETED);
        assertThat(detail.completions().get(ResearchStage.REVIEW).basis()).isEqualTo(
                com.portfolio.invest.domain.research.CompletionBasis.MANUAL);
        assertThat(detail.strategy()).isNull();
        assertThat(detail.falsifiers()).isEmpty();
    }

    @DisplayName("PATCH 标题与阶段：rename + changeStage 落库并写阶段变更事件")
    @Test
    void givenTitleAndStage_whenPatch_thenRenamedAndStageChanged() {
        ResearchProject source = project(5L, 1L, ResearchStage.NEW_ANALYSIS, ProjectStatus.ACTIVE);
        when(repository.findById(5L)).thenReturn(Optional.of(source));
        when(repository.save(any(ResearchProject.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findManualStates(5L)).thenReturn(Map.of());
        when(repository.findStrategy(5L)).thenReturn(Optional.empty());

        var detail = service.updateProject(1L, 5L,
                new UpdateProjectCommand(" 新标题 ", ResearchStage.STRATEGY, null));

        assertThat(detail.project().title()).isEqualTo("新标题");
        assertThat(detail.project().currentStage()).isEqualTo(ResearchStage.STRATEGY);
        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().title()).isEqualTo("阶段变更：制定投资策略");
    }

    // —— 策略两级状态机 ——

    @DisplayName("finalize 校验传导：估值下限 ≥ 上限 → VALUATION_RANGE_INVALID，不落库不写事件")
    @Test
    void givenInvalidValuationRange_whenFinalize_thenRejectedWithoutSideEffects() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.STRATEGY, ProjectStatus.ACTIVE)));
        when(repository.findStrategy(5L)).thenReturn(Optional.of(strategy(StrategyState.DRAFT, "20", "10")));

        assertThatThrownBy(() -> service.finalizeStrategy(1L, 5L))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.VALUATION_RANGE_INVALID));
        verify(repository, never()).saveStrategy(any(StrategyDoc.class));
        verify(journalRepository, never()).save(any(JournalEntry.class));
    }

    @DisplayName("finalize 成功：置 FINALIZED + 写「策略定稿」事件")
    @Test
    void givenCompleteDraft_whenFinalize_thenFinalizedWithEvent() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.STRATEGY, ProjectStatus.ACTIVE)));
        when(repository.findStrategy(5L)).thenReturn(Optional.of(strategy(StrategyState.DRAFT, "10", "20")));
        when(repository.saveStrategy(any(StrategyDoc.class))).thenAnswer(inv -> inv.getArgument(0));

        var view = service.finalizeStrategy(1L, 5L);

        assertThat(view.state()).isEqualTo(StrategyState.FINALIZED);
        assertThat(view.finalizedAt()).isNotNull();
        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().title()).isEqualTo("策略定稿");
    }

    @DisplayName("FINALIZED 态 PUT 暂存 → STRATEGY_FINALIZED 拒绝（须先 revise）")
    @Test
    void givenFinalizedStrategy_whenSaveDraft_thenRejected() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.STRATEGY, ProjectStatus.ACTIVE)));
        when(repository.findStrategy(5L)).thenReturn(Optional.of(strategy(StrategyState.FINALIZED, "10", "20")));

        assertThatThrownBy(() -> service.saveStrategyDraft(1L, 5L,
                new SaveStrategyCommand("新逻辑", null, null, null, null, null)))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.STRATEGY_FINALIZED));
        verify(repository, never()).saveStrategy(any(StrategyDoc.class));
    }

    @DisplayName("无策略文档时 PUT 暂存：以 draftOf 新建草稿保存")
    @Test
    void givenNoStrategy_whenSaveDraft_thenDraftCreated() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.STRATEGY, ProjectStatus.ACTIVE)));
        when(repository.findStrategy(5L)).thenReturn(Optional.empty());
        when(repository.saveStrategy(any(StrategyDoc.class))).thenAnswer(inv -> inv.getArgument(0));

        var view = service.saveStrategyDraft(1L, 5L,
                new SaveStrategyCommand("扩产逻辑", new BigDecimal("10"), new BigDecimal("20"), "两成仓", null, null));

        assertThat(view.state()).isEqualTo(StrategyState.DRAFT);
        assertThat(view.thesis()).isEqualTo("扩产逻辑");
        ArgumentCaptor<StrategyDoc> captor = ArgumentCaptor.forClass(StrategyDoc.class);
        verify(repository).saveStrategy(captor.capture());
        assertThat(captor.getValue().projectId()).isEqualTo(5L);
        assertThat(captor.getValue().valuationLow()).isEqualByComparingTo("10");
    }

    @DisplayName("revise：FINALIZED→DRAFT 写事件；DRAFT 态宽容 no-op 不写事件")
    @Test
    void givenStrategies_whenRevise_thenTransitionWritesEventButDraftNoop() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.STRATEGY, ProjectStatus.ACTIVE)));
        when(repository.saveStrategy(any(StrategyDoc.class))).thenAnswer(inv -> inv.getArgument(0));

        when(repository.findStrategy(5L)).thenReturn(Optional.of(strategy(StrategyState.FINALIZED, "10", "20")));
        var revised = service.reviseStrategy(1L, 5L);
        assertThat(revised.state()).isEqualTo(StrategyState.DRAFT);
        verify(journalRepository, times(1)).save(any(JournalEntry.class));

        when(repository.findStrategy(5L)).thenReturn(Optional.of(strategy(StrategyState.DRAFT, "10", "20")));
        var noop = service.reviseStrategy(1L, 5L);
        assertThat(noop.state()).isEqualTo(StrategyState.DRAFT);
        verify(journalRepository, times(1)).save(any(JournalEntry.class)); // 仍只一次
    }

    @DisplayName("无策略文档 finalize/revise/GET → NOT_FOUND")
    @Test
    void givenNoStrategy_whenFinalizeOrReviseOrGet_thenNotFound() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.STRATEGY, ProjectStatus.ACTIVE)));
        when(repository.findStrategy(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.finalizeStrategy(1L, 5L))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.reviseStrategy(1L, 5L))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.getStrategy(1L, 5L))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.NOT_FOUND));
    }

    // —— 证伪条件 ——

    @DisplayName("无策略文档 PUT 证伪条件 → STRATEGY_REQUIRED")
    @Test
    void givenNoStrategy_whenSaveFalsifiers_thenStrategyRequired() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.STRATEGY, ProjectStatus.ACTIVE)));
        when(repository.findStrategy(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.saveFalsifiers(1L, 5L, List.of(
                new SaveFalsifierItem(FalsifierKind.PREDICATE, FalsifierPredicate.PRICE_BELOW,
                        new BigDecimal("13.5"), null, null))))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.STRATEGY_REQUIRED));
    }

    @DisplayName("PUT 证伪条件：PREDICATE/EVENT 按工厂构造整替保存，回读返回视图")
    @Test
    void givenStrategy_whenSaveFalsifiers_thenReplacedAndViewsReturned() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.STRATEGY, ProjectStatus.ACTIVE)));
        when(repository.findStrategy(5L)).thenReturn(Optional.of(strategy(StrategyState.DRAFT, "10", "20")));
        when(repository.findFalsifiers(7L)).thenReturn(List.of(
                Falsifier.reconstitute(9L, 7L, FalsifierKind.PREDICATE, FalsifierPredicate.PRICE_BELOW,
                        new BigDecimal("13.5"), false, "跌破下限", true, 0L, NOW, NOW),
                Falsifier.reconstitute(10L, 7L, FalsifierKind.EVENT, null,
                        null, false, "扩产延期", true, 0L, NOW, NOW)));

        var views = service.saveFalsifiers(1L, 5L, List.of(
                new SaveFalsifierItem(FalsifierKind.PREDICATE, FalsifierPredicate.PRICE_BELOW,
                        new BigDecimal("13.5"), null, "跌破下限"),
                new SaveFalsifierItem(FalsifierKind.EVENT, null, null, null, "扩产延期")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Falsifier>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(repository).saveFalsifiers(eq(7L), captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue().get(0).kind()).isEqualTo(FalsifierKind.PREDICATE);
        assertThat(captor.getValue().get(0).strategyId()).isEqualTo(7L);
        assertThat(views).hasSize(2);
        assertThat(views.get(0).id()).isEqualTo(9L);
        assertThat(views.get(1).kind()).isEqualTo(FalsifierKind.EVENT);
    }

    @DisplayName("PUT EVENT 携 eventChecked=true：勾选位经整替重建携带落库（PREDICATE 忽略该字段）")
    @Test
    void givenEventItemWithChecked_whenSaveFalsifiers_thenFlagCarriedIntoRebuiltRows() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.STRATEGY, ProjectStatus.ACTIVE)));
        when(repository.findStrategy(5L)).thenReturn(Optional.of(strategy(StrategyState.DRAFT, "10", "20")));
        when(repository.findFalsifiers(7L)).thenReturn(List.of(
                Falsifier.reconstitute(10L, 7L, FalsifierKind.EVENT, null, null,
                        true, "扩产延期已确认", true, 0L, NOW, NOW)));

        var views = service.saveFalsifiers(1L, 5L, List.of(
                new SaveFalsifierItem(FalsifierKind.EVENT, null, null, Boolean.TRUE, "扩产延期已确认"),
                new SaveFalsifierItem(FalsifierKind.PREDICATE, FalsifierPredicate.PRICE_BELOW,
                        new BigDecimal("13.5"), Boolean.TRUE, "跌破下限")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Falsifier>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(repository).saveFalsifiers(eq(7L), captor.capture());
        assertThat(captor.getValue().get(0).kind()).isEqualTo(FalsifierKind.EVENT);
        assertThat(captor.getValue().get(0).eventChecked()).isTrue();  // EVENT 勾选位随整替项落库
        assertThat(captor.getValue().get(1).eventChecked()).isFalse(); // PREDICATE 不消费 eventChecked
        assertThat(views.get(0).eventChecked()).isTrue(); // 回读视图同位（落库往返口径）
    }

    @DisplayName("GET 证伪条件：无策略文档返回空列表（与详情视图一致）")
    @Test
    void givenNoStrategy_whenGetFalsifiers_thenEmptyList() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.STRATEGY, ProjectStatus.ACTIVE)));
        when(repository.findStrategy(5L)).thenReturn(Optional.empty());
        assertThat(service.getFalsifiers(1L, 5L)).isEmpty();
    }

    // —— 列表与详情读模型 ——

    @DisplayName("列表：默认查 ACTIVE（归档不出现），stage/q 内存过滤")
    @Test
    void givenProjects_whenList_thenActiveByDefaultAndStageQFiltered() {
        ResearchProject maotai = project(5L, 1L, ResearchStage.NEW_ANALYSIS, ProjectStatus.ACTIVE);
        ResearchProject yanhuang = ResearchProject.reconstitute(6L, 1L, "000858", "五粮液", null,
                "五粮液研究", ResearchStage.STRATEGY, ProjectStatus.ACTIVE, 0L, NOW, NOW);
        when(repository.findByUserId(1L, ProjectStatus.ACTIVE)).thenReturn(List.of(yanhuang, maotai));

        assertThat(service.listProjects(1L, null, null, null)).hasSize(2);
        assertThat(service.listProjects(1L, ResearchStage.STRATEGY, null, null))
                .extracting(v -> v.id()).containsExactly(6L);
        assertThat(service.listProjects(1L, null, null, "茅台")).hasSize(1);
        assertThat(service.listProjects(1L, null, null, "000858")).hasSize(1);
        assertThat(service.listProjects(1L, null, null, "不存在")).isEmpty();
        // status 缺省的 5 次列表查询全部落 ACTIVE（归档不出现）
        verify(repository, times(5)).findByUserId(1L, ProjectStatus.ACTIVE);

        // 显式 ?status=ARCHIVED 透传仓库
        service.listProjects(1L, null, ProjectStatus.ARCHIVED, null);
        verify(repository).findByUserId(1L, ProjectStatus.ARCHIVED);
    }

    @DisplayName("详情完成度：NEW_ANALYSIS 立项即 AUTO 完成，定稿策略 STRATEGY AUTO 完成，当前阶段进行中")
    @Test
    void givenFinalizedStrategy_whenGetDetail_thenCompletionsDerived() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.POSITION, ProjectStatus.ACTIVE)));
        when(repository.findManualStates(5L)).thenReturn(Map.of());
        when(repository.findStrategy(5L)).thenReturn(Optional.of(strategy(StrategyState.FINALIZED, "10", "20")));
        when(repository.findFalsifiers(7L)).thenReturn(List.of());

        var detail = service.getProject(1L, 5L);

        assertThat(detail.completions().get(ResearchStage.NEW_ANALYSIS).status()).isEqualTo(StageStatus.COMPLETED);
        assertThat(detail.completions().get(ResearchStage.NEW_ANALYSIS).basis())
                .isEqualTo(com.portfolio.invest.domain.research.CompletionBasis.AUTO);
        assertThat(detail.completions().get(ResearchStage.STRATEGY).status()).isEqualTo(StageStatus.COMPLETED);
        assertThat(detail.completions().get(ResearchStage.POSITION).status()).isEqualTo(StageStatus.IN_PROGRESS);
        assertThat(detail.completions().get(ResearchStage.REVIEW).status()).isEqualTo(StageStatus.NOT_STARTED);
        assertThat(detail.strategy().state()).isEqualTo(StrategyState.FINALIZED);
    }

    @DisplayName("手动 REOPENED 覆盖 AUTO：完成度显示 IN_PROGRESS（优先级 REOPENED > AUTO）")
    @Test
    void givenReopenedOverride_whenGetDetail_thenInProgress() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.STRATEGY, ProjectStatus.ACTIVE)));
        when(repository.findManualStates(5L)).thenReturn(Map.of(ResearchStage.NEW_ANALYSIS, ManualState.REOPENED));
        when(repository.findStrategy(5L)).thenReturn(Optional.empty());

        var detail = service.getProject(1L, 5L);

        assertThat(detail.completions().get(ResearchStage.NEW_ANALYSIS).status()).isEqualTo(StageStatus.IN_PROGRESS);
    }

    @DisplayName("POSITION 完成度回接真实产物：建仓计划 + 检查留痕齐 → 完成·自动（目录 2 件）")
    @Test
    void givenEntryPlanAndCheckRecord_whenGetDetail_thenPositionCompletedAuto() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.POSITION, ProjectStatus.ACTIVE)));
        when(repository.findManualStates(5L)).thenReturn(Map.of());
        when(repository.findStrategy(5L)).thenReturn(Optional.empty());
        when(entryPlanRepository.findByProjectId(5L)).thenReturn(Optional.of(EntryPlan.reconstitute(11L, 5L,
                null, null, List.of(new EntryBatch(1, new BigDecimal("12"), new BigDecimal("13"), 100L, null,
                        new BigDecimal("1.0"))), 0L, NOW, NOW)));
        when(checkRepository.findChecks(5L)).thenReturn(List.of(CheckRecord.reconstitute(21L, 5L, CheckType.BUY,
                List.of(new CheckItemResult("能力圈", null, null, CheckOutcome.PASS)), CheckResult.CONFIRMED,
                null, NOW)));

        var detail = service.getProject(1L, 5L);

        assertThat(detail.completions().get(ResearchStage.POSITION).status()).isEqualTo(StageStatus.COMPLETED);
        assertThat(detail.completions().get(ResearchStage.POSITION).basis())
                .isEqualTo(com.portfolio.invest.domain.research.CompletionBasis.AUTO);
    }

    @DisplayName("POSITION 产物不齐：仅有建仓计划无检查留痕 → 进行中（1/2 件，当前阶段）")
    @Test
    void givenOnlyEntryPlan_whenGetDetail_thenPositionInProgress() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.POSITION, ProjectStatus.ACTIVE)));
        when(repository.findManualStates(5L)).thenReturn(Map.of());
        when(repository.findStrategy(5L)).thenReturn(Optional.empty());
        when(entryPlanRepository.findByProjectId(5L)).thenReturn(Optional.of(EntryPlan.reconstitute(11L, 5L,
                null, null, List.of(new EntryBatch(1, new BigDecimal("12"), new BigDecimal("13"), 100L, null,
                        new BigDecimal("1.0"))), 0L, NOW, NOW)));
        when(checkRepository.findChecks(5L)).thenReturn(List.of());

        var detail = service.getProject(1L, 5L);

        assertThat(detail.completions().get(ResearchStage.POSITION).status()).isEqualTo(StageStatus.IN_PROGRESS);
        assertThat(detail.completions().get(ResearchStage.POSITION).basis())
                .isEqualTo(com.portfolio.invest.domain.research.CompletionBasis.PENDING);
    }

    @DisplayName("REVIEW 完成度回接真实产物：有复盘记录 → 完成·自动（目录 1 件）")
    @Test
    void givenReviewRecord_whenGetDetail_thenReviewCompletedAuto() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.REVIEW, ProjectStatus.ACTIVE)));
        when(repository.findManualStates(5L)).thenReturn(Map.of());
        when(repository.findStrategy(5L)).thenReturn(Optional.empty());
        when(reviewRepository.findByProjectId(5L)).thenReturn(List.of(
                persistedReview(RefluxState.PENDING, null, null)));

        var detail = service.getProject(1L, 5L);

        assertThat(detail.completions().get(ResearchStage.REVIEW).status()).isEqualTo(StageStatus.COMPLETED);
        assertThat(detail.completions().get(ResearchStage.REVIEW).basis())
                .isEqualTo(com.portfolio.invest.domain.research.CompletionBasis.AUTO);
    }

    @DisplayName("保存后列表/详情走同一仓库：saveFalsifiers 后 GET 回读")
    @Test
    void givenSavedFalsifiers_whenGet_thenSameList() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.STRATEGY, ProjectStatus.ACTIVE)));
        when(repository.findStrategy(5L)).thenReturn(Optional.of(strategy(StrategyState.DRAFT, "10", "20")));
        when(repository.findFalsifiers(7L)).thenReturn(List.of(
                Falsifier.reconstitute(9L, 7L, FalsifierKind.EVENT, null, null, false, "扩产延期", true, 0L, NOW, NOW)));

        assertThat(service.getFalsifiers(1L, 5L)).hasSize(1);
        verify(repository).findFalsifiers(7L);
    }

    // —— 建仓计划（P3-T4）——

    @DisplayName("PUT 建仓计划：of() 重建整替保存，视图携带 kellyRatio（Ruling-15：0.6/2.0→0.4）")
    @Test
    void givenPlanCommand_whenSave_thenRebuiltOfAndSaved() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.POSITION, ProjectStatus.ACTIVE)));
        when(entryPlanRepository.save(any(EntryPlan.class))).thenAnswer(inv -> {
            EntryPlan plan = inv.getArgument(0);
            return EntryPlan.reconstitute(11L, plan.projectId(), plan.winRate(), plan.payoffRatio(),
                    plan.batches(), 0L, NOW, NOW);
        });

        var view = service.saveEntryPlan(1L, 5L, new SaveEntryPlanCommand(
                new BigDecimal("0.6"), new BigDecimal("2.0"), List.of(
                new SaveEntryBatchItem(1, new BigDecimal("12"), new BigDecimal("13"), 100L, null, new BigDecimal("0.6")),
                new SaveEntryBatchItem(2, new BigDecimal("10"), new BigDecimal("11"), 100L, new BigDecimal("1200"), new BigDecimal("0.4")))));

        ArgumentCaptor<EntryPlan> captor = ArgumentCaptor.forClass(EntryPlan.class);
        verify(entryPlanRepository).save(captor.capture());
        assertThat(captor.getValue().projectId()).isEqualTo(5L);
        assertThat(captor.getValue().batches()).hasSize(2);
        assertThat(view.id()).isEqualTo(11L);
        assertThat(view.kellyRatio()).isEqualByComparingTo("0.4");
        assertThat(view.batches()).hasSize(2);
        assertThat(view.batches().get(1).amount()).isEqualByComparingTo("1200");
    }

    @DisplayName("PUT 建仓计划 Σratio > 1 → RATIO_SUM_EXCEEDED，不落库")
    @Test
    void givenRatioSumOverOne_whenSave_thenRejectedWithoutSave() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.POSITION, ProjectStatus.ACTIVE)));

        assertThatThrownBy(() -> service.saveEntryPlan(1L, 5L, new SaveEntryPlanCommand(
                null, null, List.of(
                new SaveEntryBatchItem(1, new BigDecimal("12"), new BigDecimal("13"), 100L, null, new BigDecimal("0.6")),
                new SaveEntryBatchItem(2, new BigDecimal("10"), new BigDecimal("11"), 100L, null, new BigDecimal("0.5"))))))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.RATIO_SUM_EXCEEDED));
        verify(entryPlanRepository, never()).save(any(EntryPlan.class));
    }

    @DisplayName("PUT 建仓计划 null 批次元素/空列表 → 组装层守卫（T1 deferred），不落库")
    @Test
    void givenNullBatchItem_whenSave_thenGuardedWithoutSave() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.POSITION, ProjectStatus.ACTIVE)));

        List<SaveEntryBatchItem> withNull = new java.util.ArrayList<>();
        withNull.add(null);
        assertThatThrownBy(() -> service.saveEntryPlan(1L, 5L,
                new SaveEntryPlanCommand(null, null, withNull)))
                .isInstanceOf(ResearchException.class);
        assertThatThrownBy(() -> service.saveEntryPlan(1L, 5L,
                new SaveEntryPlanCommand(null, null, null)))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.BATCH_REQUIRED));
        verify(entryPlanRepository, never()).save(any(EntryPlan.class));
    }

    @DisplayName("GET 建仓计划：未保存 → NOT_FOUND；已保存 → 视图回读")
    @Test
    void givenPlanPresence_whenGet_thenViewOrNotFound() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.POSITION, ProjectStatus.ACTIVE)));
        when(entryPlanRepository.findByProjectId(5L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getEntryPlan(1L, 5L))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.NOT_FOUND));

        when(entryPlanRepository.findByProjectId(5L)).thenReturn(Optional.of(EntryPlan.reconstitute(11L, 5L,
                new BigDecimal("0.6"), new BigDecimal("2.0"),
                List.of(new EntryBatch(1, new BigDecimal("12"), new BigDecimal("13"), 100L, null,
                        new BigDecimal("1.0"))), 0L, NOW, NOW)));
        var view = service.getEntryPlan(1L, 5L);
        assertThat(view.kellyRatio()).isEqualByComparingTo("0.4");
        assertThat(view.batches()).hasSize(1);
    }

    // —— 纪律检查 preview / submit ——

    @DisplayName("preview：组装上下文调纯函数返回命中项，零写库（不落库不留痕）")
    @Test
    void givenCheckRequest_whenPreview_thenItemsReturnedWithoutAnyWrite() {
        ResearchProject project = project(5L, 1L, ResearchStage.POSITION, ProjectStatus.ACTIVE);
        when(repository.findById(5L)).thenReturn(Optional.of(project));
        when(entryPlanRepository.findByProjectId(5L)).thenReturn(Optional.of(EntryPlan.reconstitute(11L, 5L,
                null, null, List.of(new EntryBatch(1, new BigDecimal("12"), new BigDecimal("13"), 100L, null,
                        new BigDecimal("0.3"))), 0L, NOW, NOW)));
        when(repository.findStrategy(5L)).thenReturn(Optional.of(strategy(StrategyState.FINALIZED, "10", "20")));
        when(repository.findFalsifiers(7L)).thenReturn(List.of());
        MarketSnapshot snapshot = new MarketSnapshot(new BigDecimal("12.34"), new BigDecimal("25.5"),
                new BigDecimal("8.2"), "东财收盘及估值 2026-09-28");
        when(snapshotAssembler.assemble("600519")).thenReturn(snapshot);
        when(orchestration.enabledRules(1L)).thenReturn(List.of(
                new RuleInput("SINGLE_POSITION_RATIO", new BigDecimal("0.5"))));
        when(orchestration.buildContext(eq(project), eq(new BigDecimal("0.3")), any(), any(), eq(snapshot)))
                .thenReturn(new CheckContext(new BigDecimal("0.3"), null, new BigDecimal("25.5"),
                        new BigDecimal("8.2"), List.of("能力圈", "安全边际", "估值核对", "买入条件"), List.of()));

        Map<String, Boolean> f01 = new LinkedHashMap<>();
        f01.put("能力圈", true);
        f01.put("安全边际", true);
        f01.put("估值核对", true);
        f01.put("买入条件", true);
        List<CheckItemResult> items = service.previewCheck(1L, 5L, new PreviewCheckCommand(CheckType.BUY, f01));

        // 4 规则项（1 条启用规则 + 3 条未配置 UNSET）+ F01 四项；启用规则 0.3 ≤ 0.5 → PASS
        assertThat(items).hasSize(8);
        assertThat(items.get(0).metric()).isEqualTo("SINGLE_POSITION_RATIO");
        assertThat(items.get(0).outcome()).isEqualTo(CheckOutcome.PASS);
        assertThat(items.get(1).outcome()).isEqualTo(CheckOutcome.UNSET);
        assertThat(items.get(4).metric()).isEqualTo("能力圈");
        assertThat(items.get(4).outcome()).isEqualTo(CheckOutcome.PASS);

        // 零写库：无任何 save/insert/事件（Review Focus「preview 不落库」）
        verify(entryPlanRepository, never()).save(any(EntryPlan.class));
        verify(checkRepository, never()).insert(any(CheckRecord.class));
        verify(journalRepository, never()).save(any(JournalEntry.class));
    }

    @DisplayName("preview 无建仓计划：planRatio=null 仍可组装上下文（仅估值/规则项）")
    @Test
    void givenNoEntryPlan_whenPreview_thenContextBuiltWithNullPlanRatio() {
        ResearchProject project = project(5L, 1L, ResearchStage.POSITION, ProjectStatus.ACTIVE);
        when(repository.findById(5L)).thenReturn(Optional.of(project));
        when(entryPlanRepository.findByProjectId(5L)).thenReturn(Optional.empty());
        when(repository.findStrategy(5L)).thenReturn(Optional.empty());
        when(snapshotAssembler.assemble("600519")).thenReturn(new MarketSnapshot(null, null, null, "东财收盘 2026-09-28"));
        when(orchestration.enabledRules(1L)).thenReturn(List.of());
        when(orchestration.buildContext(eq(project), eq(null), any(), any(), any()))
                .thenReturn(new CheckContext(null, null, null, null, List.of(), List.of()));

        List<CheckItemResult> items = service.previewCheck(1L, 5L, new PreviewCheckCommand(CheckType.SELL, null));

        // SELL：4 规则全 UNSET + F01 四项全 HIT（未勾选）；无证伪条件（无策略）不注入核对项
        assertThat(items).hasSize(8);
        assertThat(items.get(0).outcome()).isEqualTo(CheckOutcome.UNSET);
        assertThat(items.get(4).outcome()).isEqualTo(CheckOutcome.HIT);
        verify(orchestration).buildContext(eq(project), eq(null), eq(List.of()), eq(List.of()), any());
    }

    @DisplayName("preview F01 勾选布尔转换：true 项进上下文，false/缺省不进")
    @Test
    void givenMixedF01Booleans_whenPreview_thenOnlyCheckedItemsPassed() {
        ResearchProject project = project(5L, 1L, ResearchStage.POSITION, ProjectStatus.ACTIVE);
        when(repository.findById(5L)).thenReturn(Optional.of(project));
        when(entryPlanRepository.findByProjectId(5L)).thenReturn(Optional.empty());
        when(repository.findStrategy(5L)).thenReturn(Optional.empty());
        when(snapshotAssembler.assemble("600519")).thenReturn(new MarketSnapshot(null, null, null, null));
        when(orchestration.enabledRules(1L)).thenReturn(List.of());
        when(orchestration.buildContext(any(), any(), any(), any(), any()))
                .thenReturn(new CheckContext(null, null, null, null, List.of("能力圈"), List.of()));

        Map<String, Boolean> f01 = new LinkedHashMap<>();
        f01.put("能力圈", true);
        f01.put("安全边际", false);
        service.previewCheck(1L, 5L, new PreviewCheckCommand(CheckType.BUY, f01));

        verify(orchestration).buildContext(any(), any(), eq(List.of("能力圈")), any(), any());
    }

    @DisplayName("submit：OVERRIDDEN 缺理由 → OVERRIDE_REASON_REQUIRED，不落库不写事件（Review Focus 1）")
    @Test
    void givenOverriddenWithoutReason_whenSubmit_thenRejectedWithoutSideEffects() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.POSITION, ProjectStatus.ACTIVE)));

        assertThatThrownBy(() -> service.submitCheck(1L, 5L, new SubmitCheckCommand(CheckType.BUY,
                CheckResult.OVERRIDDEN, "  ", List.of(new CheckItemResult("能力圈", null, null, CheckOutcome.PASS)))))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.OVERRIDE_REASON_REQUIRED));
        verify(checkRepository, never()).insert(any(CheckRecord.class));
        verify(journalRepository, never()).save(any(JournalEntry.class));
    }

    @DisplayName("submit：append-only 落库 + journal 事件「纪律检查：<类型>/<结论>」，CONFIRMED 理由置空")
    @Test
    void givenConfirmedCheck_whenSubmit_thenRecordInsertedAndEventWritten() {
        ResearchProject project = project(5L, 1L, ResearchStage.POSITION, ProjectStatus.ACTIVE);
        when(repository.findById(5L)).thenReturn(Optional.of(project));
        when(checkRepository.insert(any(CheckRecord.class))).thenAnswer(inv -> {
            CheckRecord record = inv.getArgument(0);
            return CheckRecord.reconstitute(21L, record.projectId(), record.checkType(), record.items(),
                    record.result(), record.overrideReason(), record.createdAt());
        });
        List<CheckItemResult> items = List.of(
                new CheckItemResult("SINGLE_POSITION_RATIO", new BigDecimal("0.5"), new BigDecimal("0.3"), CheckOutcome.PASS),
                new CheckItemResult("能力圈", null, null, CheckOutcome.HIT));

        var view = service.submitCheck(1L, 5L,
                new SubmitCheckCommand(CheckType.BUY, CheckResult.CONFIRMED, "应被忽略的理由", items));

        ArgumentCaptor<CheckRecord> recordCaptor = ArgumentCaptor.forClass(CheckRecord.class);
        verify(checkRepository).insert(recordCaptor.capture());
        assertThat(recordCaptor.getValue().projectId()).isEqualTo(5L);
        assertThat(recordCaptor.getValue().overrideReason()).isNull(); // CONFIRMED 理由忽略置 null
        assertThat(view.id()).isEqualTo(21L);
        assertThat(view.items()).hasSize(2);
        ArgumentCaptor<JournalEntry> eventCaptor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalRepository, times(1)).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().type()).isEqualTo(JournalEntryType.RESEARCH_EVENT);
        assertThat(eventCaptor.getValue().title()).isEqualTo("纪律检查：买入/确认");
        assertThat(eventCaptor.getValue().projectId()).isEqualTo(5L);
    }

    @DisplayName("submit：OVERRIDDEN 携理由留痕（理由入库 + 事件「卖出/越过」）")
    @Test
    void givenOverriddenWithReason_whenSubmit_thenReasonPersistedAndEventWritten() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.POSITION, ProjectStatus.ACTIVE)));
        when(checkRepository.insert(any(CheckRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        var view = service.submitCheck(1L, 5L, new SubmitCheckCommand(CheckType.SELL, CheckResult.OVERRIDDEN,
                "计划外机会，仓位已复核", List.of(new CheckItemResult("能力圈", null, null, CheckOutcome.HIT))));

        assertThat(view.overrideReason()).isEqualTo("计划外机会，仓位已复核");
        ArgumentCaptor<JournalEntry> eventCaptor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalRepository).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().title()).isEqualTo("纪律检查：卖出/越过");
    }

    @DisplayName("非本人项目 entry-plan/checks/hits 全部 → NOT_FOUND（不泄漏存在性）")
    @Test
    void givenOthersProject_whenNewEndpoints_thenNotFound() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 2L,
                ResearchStage.POSITION, ProjectStatus.ACTIVE)));

        assertThatThrownBy(() -> service.getEntryPlan(1L, 5L))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.saveEntryPlan(1L, 5L, new SaveEntryPlanCommand(null, null, List.of())))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.previewCheck(1L, 5L, new PreviewCheckCommand(CheckType.BUY, null)))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.submitCheck(1L, 5L, new SubmitCheckCommand(CheckType.BUY,
                CheckResult.CONFIRMED, null, List.of(new CheckItemResult("能力圈", null, null, CheckOutcome.PASS)))))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.getHits(1L, 5L))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.NOT_FOUND));
        verifyNoWrites();
    }

    // —— 证伪命中合并视图（D21 实时 + 历史合并，Ruling-18）——

    @DisplayName("getHits：实时求值（价格命中可解释 basis）+ EVENT 按勾选状态展示，无历史行")
    @Test
    void givenFalsifiersAndSnapshot_whenGetHits_thenRealtimeEvaluated() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.POSITION, ProjectStatus.ACTIVE)));
        when(repository.findStrategy(5L)).thenReturn(Optional.of(strategy(StrategyState.FINALIZED, "10", "20")));
        when(repository.findFalsifiers(7L)).thenReturn(List.of(
                Falsifier.reconstitute(9L, 7L, FalsifierKind.PREDICATE, FalsifierPredicate.PRICE_BELOW,
                        new BigDecimal("13.5"), false, "跌破下限", true, 0L, NOW, NOW),
                Falsifier.reconstitute(10L, 7L, FalsifierKind.EVENT, null, null, false, "扩产延期", true, 0L, NOW, NOW),
                Falsifier.reconstitute(11L, 7L, FalsifierKind.EVENT, null, null, true, "批价转负（已确认）", true, 0L, NOW, NOW)));
        when(snapshotAssembler.assemble("600519")).thenReturn(new MarketSnapshot(new BigDecimal("12.34"),
                new BigDecimal("25.5"), null, "东财收盘及估值 2026-09-28"));
        when(checkRepository.findHits(5L)).thenReturn(List.of());

        var views = service.getHits(1L, 5L);

        assertThat(views).hasSize(3);
        var price = views.get(0);
        assertThat(price.falsifierId()).isEqualTo(9L);
        assertThat(price.hit()).isTrue();
        assertThat(price.basis()).contains("收盘价 12.34").contains("东财收盘及估值");
        assertThat(price.realtime()).isTrue();
        var uncheckedEvent = views.get(1);
        assertThat(uncheckedEvent.pending()).isTrue(); // Ruling-18：未勾选 → 待人工勾选
        assertThat(uncheckedEvent.basis()).isEqualTo("待人工勾选");
        var checkedEvent = views.get(2);
        assertThat(checkedEvent.pending()).isFalse(); // Ruling-18：已勾选 → 已确认事件
        assertThat(checkedEvent.basis()).isEqualTo("已确认事件");
        verifyNoWrites();
    }

    @DisplayName("getHits：实时命中（缺收盘价 → skipped 不自动命中）+ 历史 hit 留痕行合并")
    @Test
    void givenNoCloseAndHistoryHits_whenGetHits_thenSkippedLiveAndHistoryMerged() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.POSITION, ProjectStatus.ACTIVE)));
        when(repository.findStrategy(5L)).thenReturn(Optional.of(strategy(StrategyState.FINALIZED, "10", "20")));
        when(repository.findFalsifiers(7L)).thenReturn(List.of(
                Falsifier.reconstitute(9L, 7L, FalsifierKind.PREDICATE, FalsifierPredicate.PRICE_BELOW,
                        new BigDecimal("13.5"), false, "跌破下限", true, 0L, NOW, NOW)));
        when(snapshotAssembler.assemble("600519")).thenReturn(new MarketSnapshot(null, null, null, null));
        when(checkRepository.findHits(5L)).thenReturn(List.of(
                new FalsifierHit(31L, 5L, 9L, "收盘价 12.10 < 下限 13.5（东财收盘 2026-09-25）", NOW)));

        var views = service.getHits(1L, 5L);

        assertThat(views).hasSize(2);
        var live = views.get(0);
        assertThat(live.skipped()).isTrue(); // 缺最近价 → skipped（Review Focus 4）
        assertThat(live.hit()).isFalse();
        var history = views.get(1);
        assertThat(history.realtime()).isFalse();
        assertThat(history.id()).isEqualTo(31L);
        assertThat(history.hitAt()).isEqualTo(NOW);
        assertThat(history.basis()).contains("2026-09-25");
        assertThat(history.kind()).isEqualTo(FalsifierKind.PREDICATE); // 历史 falsifier 信息回连
        verifyNoWrites();
    }

    @DisplayName("getHits：无策略文档 → 仅历史 hit 行")
    @Test
    void givenNoStrategy_whenGetHits_thenOnlyHistory() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.POSITION, ProjectStatus.ACTIVE)));
        when(repository.findStrategy(5L)).thenReturn(Optional.empty());
        when(checkRepository.findHits(5L)).thenReturn(List.of(
                new FalsifierHit(31L, 5L, 9L, "收盘价 12.10 < 下限 13.5", NOW)));

        var views = service.getHits(1L, 5L);
        assertThat(views).hasSize(1);
        assertThat(views.get(0).realtime()).isFalse();
        verify(snapshotAssembler, never()).assemble(any());
    }

    // —— 证伪评审（F15，P4-T2）——

    @DisplayName("submit 证伪评审：append-only 落库 + hit 回填 + journal 事件「证伪评审：<结论>」三断言")
    @Test
    void givenHitIdAndConclusion_whenSubmitReview_thenInsertAttachAndEventWritten() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.POSITION, ProjectStatus.ACTIVE)));
        when(checkRepository.findHit(5L, 31L)).thenReturn(Optional.of(
                new FalsifierHit(31L, 5L, 9L, "收盘价 12.10 < 下限 13.5（东财收盘 2026-09-25）", NOW)));
        when(falsifierReviewRepository.insert(any(FalsifierReview.class))).thenAnswer(inv -> {
            FalsifierReview r = inv.getArgument(0);
            return FalsifierReview.reconstitute(41L, r.projectId(), r.conclusion(), r.reason(), r.createdAt());
        });

        var view = service.submitFalsifierReview(1L, 5L,
                new SubmitFalsifierReviewCommand(31L, ReviewConclusion.EXIT, "跌破下限且基本面恶化"));

        // 落库：append-only 留痕（projectId + 结论 + 理由）
        ArgumentCaptor<FalsifierReview> reviewCaptor = ArgumentCaptor.forClass(FalsifierReview.class);
        verify(falsifierReviewRepository).insert(reviewCaptor.capture());
        assertThat(reviewCaptor.getValue().projectId()).isEqualTo(5L);
        assertThat(reviewCaptor.getValue().conclusion()).isEqualTo(ReviewConclusion.EXIT);
        assertThat(reviewCaptor.getValue().reason()).isEqualTo("跌破下限且基本面恶化");
        // 回填：hit 行唯一合法更新（review_id ← 41）
        verify(falsifierReviewRepository).attachReview(31L, 41L);
        // 事件：RESEARCH_EVENT「证伪评审：退出」
        ArgumentCaptor<JournalEntry> eventCaptor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalRepository, times(1)).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().type()).isEqualTo(JournalEntryType.RESEARCH_EVENT);
        assertThat(eventCaptor.getValue().title()).isEqualTo("证伪评审：退出");
        assertThat(eventCaptor.getValue().projectId()).isEqualTo(5L);
        // 视图：hitId 回显 + 非 REVISE 无提示位
        assertThat(view.id()).isEqualTo(41L);
        assertThat(view.hitId()).isEqualTo(31L);
        assertThat(view.conclusion()).isEqualTo(ReviewConclusion.EXIT);
        assertThat(view.suggestStrategyRevise()).isFalse();
    }

    @DisplayName("submit REVISE：suggestStrategyRevise=true 提示位，策略状态零触碰（Review Focus 3：不自动改）")
    @Test
    void givenReviseConclusion_whenSubmitReview_thenHintOnlyWithoutStrategyTouch() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.POSITION, ProjectStatus.ACTIVE)));
        when(falsifierReviewRepository.insert(any(FalsifierReview.class))).thenAnswer(inv -> {
            FalsifierReview r = inv.getArgument(0);
            return FalsifierReview.reconstitute(42L, r.projectId(), r.conclusion(), r.reason(), r.createdAt());
        });

        var view = service.submitFalsifierReview(1L, 5L,
                new SubmitFalsifierReviewCommand(null, ReviewConclusion.REVISE, "证伪成立，投资逻辑需修订"));

        assertThat(view.suggestStrategyRevise()).isTrue(); // 提示位
        assertThat(view.hitId()).isNull(); // 无 hitId 不回填
        // 策略零触碰：不读不写（落库与策略修订分离，避免隐式派生）
        verify(repository, never()).findStrategy(any());
        verify(repository, never()).saveStrategy(any(StrategyDoc.class));
        verify(falsifierReviewRepository, never()).attachReview(any(), any());
        ArgumentCaptor<JournalEntry> eventCaptor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalRepository, times(1)).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().title()).isEqualTo("证伪评审：修订策略");
        assertThat(eventCaptor.getValue().content()).contains("证伪成立，投资逻辑需修订");
    }

    @DisplayName("submit 理由空白 → REVIEW_REASON_REQUIRED，不落库不回填不写事件（reason 必填）")
    @Test
    void givenBlankReason_whenSubmitReview_thenRejectedWithoutSideEffects() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.POSITION, ProjectStatus.ACTIVE)));

        assertThatThrownBy(() -> service.submitFalsifierReview(1L, 5L,
                new SubmitFalsifierReviewCommand(31L, ReviewConclusion.HOLD, "  ")))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.REVIEW_REASON_REQUIRED));
        assertThatThrownBy(() -> service.submitFalsifierReview(1L, 5L,
                new SubmitFalsifierReviewCommand(null, ReviewConclusion.HOLD, null)))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.REVIEW_REASON_REQUIRED));
        assertThatThrownBy(() -> service.submitFalsifierReview(1L, 5L,
                new SubmitFalsifierReviewCommand(null, null, "有理由无结论")))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.CONCLUSION_REQUIRED));
        verify(falsifierReviewRepository, never()).insert(any(FalsifierReview.class));
        verify(falsifierReviewRepository, never()).attachReview(any(), any());
        verify(journalRepository, never()).save(any(JournalEntry.class));
    }

    @DisplayName("submit hitId 不存在或他项目命中 → NOT_FOUND，不落库不回填（404 隔离传导）")
    @Test
    void givenForeignOrMissingHit_whenSubmitReview_thenNotFound() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.POSITION, ProjectStatus.ACTIVE)));
        when(checkRepository.findHit(5L, 31L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submitFalsifierReview(1L, 5L,
                new SubmitFalsifierReviewCommand(31L, ReviewConclusion.REDUCE, "跌破下限，先减半仓")))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.NOT_FOUND));
        verify(falsifierReviewRepository, never()).insert(any(FalsifierReview.class));
        verify(falsifierReviewRepository, never()).attachReview(any(), any());
        verify(journalRepository, never()).save(any(JournalEntry.class));
    }

    @DisplayName("非本人项目 reviews 读写 → NOT_FOUND（不泄漏存在性）")
    @Test
    void givenOthersProject_whenReviewEndpoints_thenNotFound() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 2L,
                ResearchStage.POSITION, ProjectStatus.ACTIVE)));

        assertThatThrownBy(() -> service.getFalsifierReviews(1L, 5L))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.submitFalsifierReview(1L, 5L,
                new SubmitFalsifierReviewCommand(null, ReviewConclusion.HOLD, "越权评审")))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.NOT_FOUND));
        verifyNoWrites();
    }

    @DisplayName("getFalsifierReviews：评审留痕倒序列表（suggestStrategyRevise 由结论推导，列表行不带 hit 回连）")
    @Test
    void givenReviews_whenList_thenOrderedViewsWithDerivedHint() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.POSITION, ProjectStatus.ACTIVE)));
        when(falsifierReviewRepository.findReviews(5L)).thenReturn(List.of(
                FalsifierReview.reconstitute(42L, 5L, ReviewConclusion.REVISE, "证伪成立，逻辑需修订", NOW),
                FalsifierReview.reconstitute(41L, 5L, ReviewConclusion.HOLD, "维持观察", NOW)));

        var views = service.getFalsifierReviews(1L, 5L);

        assertThat(views).hasSize(2);
        assertThat(views.get(0).id()).isEqualTo(42L);
        assertThat(views.get(0).suggestStrategyRevise()).isTrue();
        assertThat(views.get(1).id()).isEqualTo(41L);
        assertThat(views.get(1).conclusion()).isEqualTo(ReviewConclusion.HOLD);
        assertThat(views.get(1).suggestStrategyRevise()).isFalse();
        assertThat(views.get(1).hitId()).isNull(); // hit→review 单向软引用，列表不反连
        verifyNoWrites();
    }

    // —— 复盘 CRUD / 回流 / 建议（F13/F14/F16，P4-T3）——

    /** 已落库复盘行（61L，2026-02 月度，快照定格含圈内 trade [3,5]）。 */
    private static final String FROZEN_SNAPSHOT =
            "{\"periodReturn\":0.05,\"priceBasis\":\"东财收盘\",\"tradeIds\":[3,5]}";

    private static Review persistedReview(RefluxState state, Long wikiEntryId, String narrative) {
        return Review.reconstitute(61L, 5L, ReviewTier.MONTHLY, LocalDate.of(2026, 2, 1),
                LocalDate.of(2026, 2, 28), null, narrative, FROZEN_SNAPSHOT, null,
                List.of(3L, 5L), state, wikiEntryId, 0L, NOW, NOW);
    }

    /** 仓库 save 回显：透传域字段并补齐 id/version（照既有 thenAnswer 先例）。 */
    private static Review reidentified(Review review) {
        return Review.reconstitute(61L, review.projectId(), review.tier(), review.periodStart(),
                review.periodEnd(), review.answersJson(), review.narrative(), review.snapshotJson(),
                review.overridesJson(), review.tradeIds(), review.refluxState(), review.wikiEntryId(),
                0L, review.createdAt(), review.updatedAt());
    }

    /** wire JsonNode → 测试字面量（answers/overrides 以对象上送、字符串落域）。 */
    private static com.fasterxml.jackson.databind.JsonNode json(String raw) {
        try {
            return new ObjectMapper().readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DisplayName("create 复盘：Composer 输出原样定格落库 + 自动圈选去重排序 + 「复盘创建」事件（F08 时间线）")
    @Test
    void givenPeriodAndTier_whenCreateReview_thenComposerOutputFrozenWithAutoCircle() {
        ResearchProject project = project(5L, 1L, ResearchStage.REVIEW, ProjectStatus.ACTIVE);
        when(repository.findById(5L)).thenReturn(Optional.of(project));
        when(entryPlanRepository.findByProjectId(5L)).thenReturn(Optional.of(EntryPlan.reconstitute(11L, 5L,
                null, null, List.of(new EntryBatch(1, new BigDecimal("12"), new BigDecimal("13"), 100L,
                        null, new BigDecimal("1.0"))), 0L, NOW, NOW)));
        String snapshot = "{\"periodReturn\":0.05,\"tradeIds\":[205,103,205]}";
        when(reviewComposer.compose(eq(1L), eq(project), eq(LocalDate.of(2026, 2, 1)),
                eq(LocalDate.of(2026, 2, 28)), anyList())).thenReturn(snapshot);
        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> reidentified(inv.getArgument(0)));

        var view = service.createReview(1L, 5L, new CreateReviewCommand(ReviewTier.MONTHLY,
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)));

        // 定格：Composer 产物原样入 auto_snapshot（无二次加工），圈选从快照 tradeIds 解析 + 收敛
        ArgumentCaptor<Review> captor = ArgumentCaptor.forClass(Review.class);
        verify(reviewRepository).save(captor.capture());
        assertThat(captor.getValue().projectId()).isEqualTo(5L);
        assertThat(captor.getValue().tier()).isEqualTo(ReviewTier.MONTHLY);
        assertThat(captor.getValue().snapshotJson()).isEqualTo(snapshot);
        assertThat(captor.getValue().tradeIds()).containsExactly(103L, 205L);
        assertThat(captor.getValue().refluxState()).isEqualTo(RefluxState.PENDING);
        assertThat(view.id()).isEqualTo(61L);
        assertThat(view.snapshot()).isEqualTo(snapshot);
        // F08 时间线：创建即写「复盘创建：<档位>」事件（含区间，与回流事件同入项目时间线）
        ArgumentCaptor<JournalEntry> eventCaptor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalRepository, times(1)).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().type()).isEqualTo(JournalEntryType.RESEARCH_EVENT);
        assertThat(eventCaptor.getValue().title()).isEqualTo("复盘创建：月度复盘");
        assertThat(eventCaptor.getValue().content()).contains("2026-02-01~2026-02-28");
        assertThat(eventCaptor.getValue().projectId()).isEqualTo(5L);
    }

    @DisplayName("create 复盘：快照缺 tradeIds 键 → 圈选为空（compose 无交易场景）")
    @Test
    void givenSnapshotWithoutTradeIds_whenCreateReview_thenEmptyCircle() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.REVIEW, ProjectStatus.ACTIVE)));
        when(entryPlanRepository.findByProjectId(5L)).thenReturn(Optional.empty());
        when(reviewComposer.compose(any(), any(), any(), any(), any()))
                .thenReturn("{\"navSeries\":\"无数据\"}");
        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> reidentified(inv.getArgument(0)));

        var view = service.createReview(1L, 5L, new CreateReviewCommand(ReviewTier.WEEKLY,
                LocalDate.of(2026, 2, 24), LocalDate.of(2026, 2, 28)));

        ArgumentCaptor<Review> captor = ArgumentCaptor.forClass(Review.class);
        verify(reviewRepository).save(captor.capture());
        assertThat(captor.getValue().tradeIds()).isEmpty();
        assertThat(view.tradeIds()).isEmpty();
    }

    @DisplayName("getReviews：仓库倒序透传为视图，快照原样回显（定格读路径不复算）")
    @Test
    void givenReviews_whenListReviews_thenOrderedViewsWithFrozenSnapshot() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.REVIEW, ProjectStatus.ACTIVE)));
        when(reviewRepository.findByProjectId(5L)).thenReturn(List.of(
                persistedReview(RefluxState.REFLOWN, 501L, "二月复盘"),
                persistedReview(RefluxState.PENDING, null, null)));

        var views = service.getReviews(1L, 5L);

        assertThat(views).hasSize(2);
        assertThat(views.get(0).refluxState()).isEqualTo(RefluxState.REFLOWN);
        assertThat(views.get(0).wikiEntryId()).isEqualTo(501L);
        assertThat(views.get(0).snapshot()).isEqualTo(FROZEN_SNAPSHOT);
        assertThat(views.get(1).narrative()).isNull();
        verify(reviewComposer, never()).compose(any(), any(), any(), any(), any()); // 读不复算
        verifyNoWrites();
    }

    @DisplayName("PUT 复盘修正：answers/overrides/narrative 整替 + trade_ids 去重排序，快照与回流状态不动（不可改）")
    @Test
    void givenCorrections_whenUpdateReview_thenAppliedWithFrozenSnapshot() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.REVIEW, ProjectStatus.ACTIVE)));
        when(reviewRepository.findByIdAndProjectId(61L, 5L))
                .thenReturn(Optional.of(persistedReview(RefluxState.PENDING, null, null)));
        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> reidentified(inv.getArgument(0)));

        var view = service.updateReview(1L, 5L, 61L, new UpdateReviewCommand(
                json("{\"q1\":\"追高\"}"), json("{\"periodReturn\":\"0.06\"}"), "事后看止损执行晚了",
                List.of(7L, 3L, 7L)));

        ArgumentCaptor<Review> captor = ArgumentCaptor.forClass(Review.class);
        verify(reviewRepository).save(captor.capture());
        assertThat(captor.getValue().answersJson()).isEqualTo("{\"q1\":\"追高\"}");
        assertThat(captor.getValue().overridesJson()).isEqualTo("{\"periodReturn\":\"0.06\"}");
        assertThat(captor.getValue().narrative()).isEqualTo("事后看止损执行晚了");
        assertThat(captor.getValue().tradeIds()).containsExactly(3L, 7L); // 去重升序（Focus 5 手动路径）
        assertThat(captor.getValue().snapshotJson()).isEqualTo(FROZEN_SNAPSHOT); // 快照不可改
        assertThat(captor.getValue().refluxState()).isEqualTo(RefluxState.PENDING);
        assertThat(view.snapshot()).isEqualTo(FROZEN_SNAPSHOT);
        assertThat(view.tradeIds()).containsExactly(3L, 7L);
        verify(journalRepository, never()).save(any(JournalEntry.class));
    }

    @DisplayName("PUT 复盘缺复盘行（越项目/不存在）→ NOT_FOUND，不落库")
    @Test
    void givenMissingReview_whenUpdateOrReflux_thenNotFound() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.REVIEW, ProjectStatus.ACTIVE)));
        when(reviewRepository.findByIdAndProjectId(61L, 5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateReview(1L, 5L, 61L,
                new UpdateReviewCommand(json("{\"q1\":\"追高\"}"), null, null, null)))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.refluxReview(1L, 5L, 61L))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.NOT_FOUND));
        verifyNoWrites();
        verify(reviewRepository, never()).save(any(Review.class));
    }

    @DisplayName("reflux：建 wiki RESEARCH_NOTE（复盘·项目·期间 + SOP_REVIEW + projectId + narrative）→ REFLOWN 记 entryId")
    @Test
    void givenNarrative_whenReflux_thenWikiEntryCreatedAndRefown() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.REVIEW, ProjectStatus.ACTIVE)));
        when(reviewRepository.findByIdAndProjectId(61L, 5L))
                .thenReturn(Optional.of(persistedReview(RefluxState.PENDING, null, "复盘叙述：追高错误")));
        when(wikiEntryRepository.save(any(WikiEntry.class))).thenAnswer(inv -> {
            WikiEntry e = inv.getArgument(0);
            return WikiEntry.reconstitute(501L, e.userId(), e.type(), e.title(), e.content(),
                    e.category(), e.industryCode(), e.createdAt(), e.updatedAt(), 0L, e.projectId());
        });
        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> reidentified(inv.getArgument(0)));

        var view = service.refluxReview(1L, 5L, 61L);

        // wiki 条目：类型/标题/category/项目软引用/内容逐字（S2 同法 SOP_TEMPLATE，D12 软引用）
        ArgumentCaptor<WikiEntry> wikiCaptor = ArgumentCaptor.forClass(WikiEntry.class);
        verify(wikiEntryRepository).save(wikiCaptor.capture());
        WikiEntry entry = wikiCaptor.getValue();
        assertThat(entry.userId()).isEqualTo(1L);
        assertThat(entry.type()).isEqualTo(WikiEntryType.RESEARCH_NOTE);
        assertThat(entry.title()).isEqualTo("复盘·茅台扩产研究·2026-02-01~2026-02-28");
        assertThat(entry.category()).isEqualTo("SOP_REVIEW");
        assertThat(entry.projectId()).isEqualTo(5L);
        assertThat(entry.content()).isEqualTo("复盘叙述：追高错误");
        // 复盘行：REFLOWN + 回填条目 id（用户确认后入库，F16）
        ArgumentCaptor<Review> reviewCaptor = ArgumentCaptor.forClass(Review.class);
        verify(reviewRepository).save(reviewCaptor.capture());
        assertThat(reviewCaptor.getValue().refluxState()).isEqualTo(RefluxState.REFLOWN);
        assertThat(reviewCaptor.getValue().wikiEntryId()).isEqualTo(501L);
        assertThat(reviewCaptor.getValue().snapshotJson()).isEqualTo(FROZEN_SNAPSHOT);
        assertThat(view.refluxState()).isEqualTo(RefluxState.REFLOWN);
        assertThat(view.wikiEntryId()).isEqualTo(501L);
        // F08 时间线：回流成功路径写「复盘回流知识库」事件（幂等早退/降级路径不写）
        ArgumentCaptor<JournalEntry> eventCaptor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalRepository, times(1)).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().type()).isEqualTo(JournalEntryType.RESEARCH_EVENT);
        assertThat(eventCaptor.getValue().title()).isEqualTo("复盘回流知识库");
        assertThat(eventCaptor.getValue().content()).contains("复盘·茅台扩产研究·2026-02-01~2026-02-28");
        assertThat(eventCaptor.getValue().projectId()).isEqualTo(5L);
    }

    @DisplayName("reflux 二次点击幂等：REFLOWN 直接返回既有 wiki_entry_id，不重复建条目不落库（Focus 4）")
    @Test
    void givenRefowned_whenRefluxAgain_thenIdempotentWithoutWikiWrite() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.REVIEW, ProjectStatus.ACTIVE)));
        when(reviewRepository.findByIdAndProjectId(61L, 5L))
                .thenReturn(Optional.of(persistedReview(RefluxState.REFLOWN, 501L, "复盘叙述")));

        var view = service.refluxReview(1L, 5L, 61L);

        assertThat(view.refluxState()).isEqualTo(RefluxState.REFLOWN);
        assertThat(view.wikiEntryId()).isEqualTo(501L);
        verify(wikiEntryRepository, never()).save(any(WikiEntry.class)); // 不重复建条目
        verify(reviewRepository, never()).save(any(Review.class)); // 不重复落库
        verify(journalRepository, never()).save(any(JournalEntry.class));
    }

    @DisplayName("reflux wiki 写异常降级：REFLUX_WIKI_UNAVAILABLE 文案抛出，REFLOWN 不落库（行保持 PENDING，不阻断）")
    @Test
    void givenWikiWriteFailure_whenReflux_thenDegradedWithoutRefown() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.REVIEW, ProjectStatus.ACTIVE)));
        when(reviewRepository.findByIdAndProjectId(61L, 5L))
                .thenReturn(Optional.of(persistedReview(RefluxState.PENDING, null, "复盘叙述")));
        when(wikiEntryRepository.save(any(WikiEntry.class)))
                .thenThrow(new RuntimeException("wiki unavailable"));

        assertThatThrownBy(() -> service.refluxReview(1L, 5L, 61L))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.REFLUX_WIKI_UNAVAILABLE))
                .hasMessageContaining("知识库");
        // 降级：REFLOWN 未写入 → 既有行保持 PENDING，复盘保存不受影响（可重试）
        verify(reviewRepository, never()).save(any(Review.class));
    }

    @DisplayName("reflux 叙述空白 → REFLUX_NARRATIVE_REQUIRED，不建 wiki 条目不落库")
    @Test
    void givenBlankNarrative_whenReflux_thenNarrativeRequired() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.REVIEW, ProjectStatus.ACTIVE)));
        when(reviewRepository.findByIdAndProjectId(61L, 5L))
                .thenReturn(Optional.of(persistedReview(RefluxState.PENDING, null, "  ")));

        assertThatThrownBy(() -> service.refluxReview(1L, 5L, 61L))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.REFLUX_NARRATIVE_REQUIRED));
        verify(wikiEntryRepository, never()).save(any(WikiEntry.class));
        verify(reviewRepository, never()).save(any(Review.class));
    }

    @DisplayName("submit 建议：stage+content+reviewId? 落库（只收集：无事件无回流副作用），reviewId 须为本项目复盘")
    @Test
    void givenFeedback_whenSubmit_thenInsertedOnly() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.REVIEW, ProjectStatus.ACTIVE)));
        when(reviewRepository.findByIdAndProjectId(61L, 5L))
                .thenReturn(Optional.of(persistedReview(RefluxState.PENDING, null, null)));
        when(feedbackRepository.insert(any(ResearchFeedback.class))).thenAnswer(inv -> {
            ResearchFeedback f = inv.getArgument(0);
            return ResearchFeedback.reconstitute(71L, f.projectId(), f.reviewId(), f.stage(),
                    f.content(), f.createdAt());
        });

        var view = service.submitFeedback(1L, 5L,
                new SubmitFeedbackCommand(61L, ResearchStage.REVIEW, "月度模板建议增加仓位口径维度"));

        ArgumentCaptor<ResearchFeedback> captor = ArgumentCaptor.forClass(ResearchFeedback.class);
        verify(feedbackRepository).insert(captor.capture());
        assertThat(captor.getValue().projectId()).isEqualTo(5L);
        assertThat(captor.getValue().reviewId()).isEqualTo(61L);
        assertThat(captor.getValue().stage()).isEqualTo(ResearchStage.REVIEW);
        assertThat(captor.getValue().content()).isEqualTo("月度模板建议增加仓位口径维度");
        assertThat(view.id()).isEqualTo(71L);
        verify(journalRepository, never()).save(any(JournalEntry.class)); // 只收集不生效（F16）
        verify(wikiEntryRepository, never()).save(any(WikiEntry.class));
    }

    @DisplayName("submit 建议内容空白 → FEEDBACK_CONTENT_REQUIRED；reviewId 越项目 → NOT_FOUND，均不落库")
    @Test
    void givenBlankContentOrForeignReview_whenSubmitFeedback_thenRejectedWithoutInsert() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.REVIEW, ProjectStatus.ACTIVE)));
        when(reviewRepository.findByIdAndProjectId(61L, 5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submitFeedback(1L, 5L,
                new SubmitFeedbackCommand(null, ResearchStage.REVIEW, "  ")))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.FEEDBACK_CONTENT_REQUIRED));
        assertThatThrownBy(() -> service.submitFeedback(1L, 5L,
                new SubmitFeedbackCommand(61L, ResearchStage.REVIEW, "合法建议")))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.NOT_FOUND));
        verify(feedbackRepository, never()).insert(any(ResearchFeedback.class));
    }

    @DisplayName("getChecks：检查留痕时间倒序透传（复盘 4.3 纪律遵守度预填数据源，P3-T4 deferred）")
    @Test
    void givenCheckRecords_whenGetChecks_thenOrderedViews() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 1L,
                ResearchStage.REVIEW, ProjectStatus.ACTIVE)));
        List<CheckItemResult> items = List.of(new CheckItemResult("能力圈", null, null, CheckOutcome.PASS));
        when(checkRepository.findChecks(5L)).thenReturn(List.of(
                CheckRecord.reconstitute(22L, 5L, CheckType.SELL, items, CheckResult.CONFIRMED,
                        null, NOW.plusSeconds(60)),
                CheckRecord.reconstitute(21L, 5L, CheckType.BUY, items, CheckResult.OVERRIDDEN,
                        "计划外机会", NOW)));

        var views = service.getChecks(1L, 5L);

        assertThat(views).hasSize(2);
        assertThat(views.get(0).id()).isEqualTo(22L); // createdAt 倒序由仓库保证，切片断言透传序
        assertThat(views.get(0).checkType()).isEqualTo(CheckType.SELL);
        assertThat(views.get(1).id()).isEqualTo(21L);
        assertThat(views.get(1).overrideReason()).isEqualTo("计划外机会");
        verifyNoWrites();
    }

    @DisplayName("非本人项目 reviews/feedback/checks 全部 → NOT_FOUND（不泄漏存在性）")
    @Test
    void givenOthersProject_whenReviewFeedbackCheckEndpoints_thenNotFound() {
        when(repository.findById(5L)).thenReturn(Optional.of(project(5L, 2L,
                ResearchStage.REVIEW, ProjectStatus.ACTIVE)));

        assertThatThrownBy(() -> service.getReviews(1L, 5L))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.createReview(1L, 5L, new CreateReviewCommand(ReviewTier.MONTHLY,
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28))))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.updateReview(1L, 5L, 61L,
                new UpdateReviewCommand(json("{\"q1\":\"追高\"}"), null, null, null)))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.refluxReview(1L, 5L, 61L))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.submitFeedback(1L, 5L,
                new SubmitFeedbackCommand(null, ResearchStage.REVIEW, "建议")))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.getChecks(1L, 5L))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.NOT_FOUND));
        verifyNoWrites();
        verify(reviewRepository, never()).save(any(Review.class));
        verify(reviewComposer, never()).compose(any(), any(), any(), any(), any());
        verify(wikiEntryRepository, never()).save(any(WikiEntry.class));
    }

    private void verifyNoWrites() {
        verify(entryPlanRepository, never()).save(any(EntryPlan.class));
        verify(checkRepository, never()).insert(any(CheckRecord.class));
        verify(falsifierReviewRepository, never()).insert(any(FalsifierReview.class));
        verify(falsifierReviewRepository, never()).attachReview(any(), any());
        verify(reviewRepository, never()).save(any(Review.class));
        verify(feedbackRepository, never()).insert(any(ResearchFeedback.class));
        verify(journalRepository, never()).save(any(JournalEntry.class));
    }
}
