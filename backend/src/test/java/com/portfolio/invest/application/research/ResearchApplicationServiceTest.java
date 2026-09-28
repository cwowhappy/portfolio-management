package com.portfolio.invest.application.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.invest.application.research.ResearchApplicationService.ManualMark;
import com.portfolio.invest.application.research.ResearchApplicationService.PreviewCheckCommand;
import com.portfolio.invest.application.research.ResearchApplicationService.SaveEntryBatchItem;
import com.portfolio.invest.application.research.ResearchApplicationService.SaveEntryPlanCommand;
import com.portfolio.invest.application.research.ResearchApplicationService.SaveFalsifierItem;
import com.portfolio.invest.application.research.ResearchApplicationService.SubmitCheckCommand;
import com.portfolio.invest.application.research.ResearchApplicationService.UpdateProjectCommand;
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
import com.portfolio.invest.domain.research.MarketSnapshot;
import com.portfolio.invest.domain.research.ProjectStatus;
import com.portfolio.invest.domain.research.ResearchCheckRepository;
import com.portfolio.invest.domain.research.ResearchEntryPlanRepository;
import com.portfolio.invest.domain.research.ResearchErrorCode;
import com.portfolio.invest.domain.research.ResearchException;
import com.portfolio.invest.domain.research.ResearchProject;
import com.portfolio.invest.domain.research.ResearchProjectRepository;
import com.portfolio.invest.domain.research.ResearchStage;
import com.portfolio.invest.domain.research.RuleInput;
import com.portfolio.invest.domain.research.StageCompletionService.ManualState;
import com.portfolio.invest.domain.research.StageStatus;
import com.portfolio.invest.domain.research.StrategyDoc;
import com.portfolio.invest.domain.research.StrategyState;
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
    private final CheckOrchestration orchestration = mock(CheckOrchestration.class);
    private final MarketSnapshotAssembler snapshotAssembler = mock(MarketSnapshotAssembler.class);
    private ResearchApplicationService service;

    @BeforeEach
    void setUp() {
        service = new ResearchApplicationService(repository, journalRepository, entryPlanRepository,
                checkRepository, orchestration, snapshotAssembler);
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
                        new BigDecimal("13.5"), null))))
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
                        new BigDecimal("13.5"), "跌破下限"),
                new SaveFalsifierItem(FalsifierKind.EVENT, null, null, "扩产延期")));

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

    private void verifyNoWrites() {
        verify(entryPlanRepository, never()).save(any(EntryPlan.class));
        verify(checkRepository, never()).insert(any(CheckRecord.class));
        verify(journalRepository, never()).save(any(JournalEntry.class));
    }
}
