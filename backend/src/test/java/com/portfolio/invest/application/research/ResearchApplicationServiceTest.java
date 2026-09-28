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
import com.portfolio.invest.application.research.ResearchApplicationService.SaveFalsifierItem;
import com.portfolio.invest.application.research.ResearchApplicationService.UpdateProjectCommand;
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
import com.portfolio.invest.domain.research.StageCompletionService.ManualState;
import com.portfolio.invest.domain.research.StageStatus;
import com.portfolio.invest.domain.research.StrategyDoc;
import com.portfolio.invest.domain.research.StrategyState;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
    private ResearchApplicationService service;

    @BeforeEach
    void setUp() {
        service = new ResearchApplicationService(repository, journalRepository);
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
}
