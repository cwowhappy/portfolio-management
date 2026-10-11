package com.portfolio.invest.application.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.invest.application.research.ResearchViews.ReportDetailView;
import com.portfolio.invest.application.research.ResearchViews.ReportSummaryView;
import com.portfolio.invest.application.research.ResearchViews.TaskStatusView;
import com.portfolio.invest.domain.journal.JournalEntry;
import com.portfolio.invest.domain.journal.JournalEntryRepository;
import com.portfolio.invest.domain.research.ProjectStatus;
import com.portfolio.invest.domain.research.ReportAnchor;
import com.portfolio.invest.domain.research.ReportAnchorRepository;
import com.portfolio.invest.domain.research.ReportAssumption;
import com.portfolio.invest.domain.research.ReportAssumptionRepository;
import com.portfolio.invest.domain.research.ReportTask;
import com.portfolio.invest.domain.research.ReportTaskRepository;
import com.portfolio.invest.domain.research.ResearchErrorCode;
import com.portfolio.invest.domain.research.ResearchException;
import com.portfolio.invest.domain.research.ResearchProject;
import com.portfolio.invest.domain.research.ResearchProjectRepository;
import com.portfolio.invest.domain.research.ResearchReport;
import com.portfolio.invest.domain.research.ResearchReportRepository;
import com.portfolio.invest.domain.research.ResearchStage;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 研报应用服务单测（MS-32 B11，设计规格 §3.2/§3.9/§3.10，Mockito 假仓库 + 固定时钟）：
 * 发起四分支（配额 402 语义异常优先 / 归属 404 / 活跃冲突 DataIntegrityViolation→
 * TASK_ACTIVE_EXISTS 409 语义 / 正常 201 语义）；轮询与列表视图；详情 staleness 注入；
 * 确认状态机（CONFIRM 写 journal 事件 / REJECT reason 必填且校验先于状态检查——
 * 终态+空白 reason → 400 而非 409 / 并发 CAS 返 0 → 409）；sink 两回调
 * （Ruling-15 不碰任务行 / Ruling-7 按 taskId 查重幂等 / 结算 settle）。
 */
class ReportApplicationServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant NOW = Instant.parse("2026-10-11T04:00:00Z"); // 上海 12:00
    private static final Long USER = 42L;
    private static final Long OTHER = 77L;
    private static final Long PROJECT_ID = 7L;
    private static final Long TASK_ID = 15L;
    private static final Long REPORT_ID = 9L;

    private final ReportTaskRepository taskRepository = mock(ReportTaskRepository.class);
    private final ResearchReportRepository reportRepository = mock(ResearchReportRepository.class);
    private final ReportAnchorRepository anchorRepository = mock(ReportAnchorRepository.class);
    private final ReportAssumptionRepository assumptionRepository = mock(ReportAssumptionRepository.class);
    private final ResearchProjectRepository projectRepository = mock(ResearchProjectRepository.class);
    private final ReportTokenQuotaService quotaService = mock(ReportTokenQuotaService.class);
    private final ReportStalenessService stalenessService = mock(ReportStalenessService.class);
    private final JournalEntryRepository journalRepository = mock(JournalEntryRepository.class);

    private final ReportApplicationService service = new ReportApplicationService(
            taskRepository, reportRepository, anchorRepository, assumptionRepository,
            projectRepository, quotaService, stalenessService, journalRepository,
            Clock.fixed(NOW, ZONE));

    // ———— 脚手架 ————

    private static ResearchProject project(Long userId) {
        return ResearchProject.reconstitute(PROJECT_ID, userId, "600519", "贵州茅台", null,
                "茅台深度研究", ResearchStage.NEW_ANALYSIS, ProjectStatus.ACTIVE, false,
                null, NOW.minusSeconds(3600), NOW.minusSeconds(3600));
    }

    private static ReportTask task(ReportTask.Status status, long tokenUsage,
                                   String errorCode, String errorMessage) {
        return ReportTask.reconstitute(TASK_ID, USER, PROJECT_ID, status,
                ReportTask.Stage.VALIDATING, tokenUsage, errorCode, errorMessage,
                NOW.minusSeconds(600), NOW, NOW.minusSeconds(300), NOW);
    }

    private static ResearchReport report(ResearchReport.Status status, String rejectedReason,
                                         Instant confirmedAt) {
        return ResearchReport.reconstitute(REPORT_ID, TASK_ID, PROJECT_ID, USER, status,
                "茅台深度研报", "现价986.50元。\n\n## 关键假设\n1. 现价986.50元处于合理区间",
                "行情接口部分失败", confirmedAt, rejectedReason,
                NOW.minusSeconds(120), NOW.minusSeconds(120));
    }

    private static ReportAnchor persistedAnchor(long id, String snippet, String state, String toolName) {
        return new ReportAnchor(id, REPORT_ID, snippet, 1, state, toolName,
                toolName == null ? null : Map.of("code", "600519"),
                toolName == null ? null : NOW, toolName == null ? null : "DATA", "raw 原值");
    }

    private static ReportTaskSink.SucceededPayload payload() {
        return new ReportTaskSink.SucceededPayload("茅台深度研报", "现价986.50元。\n\n## 关键假设\n1. 现价986.50元处于合理区间",
                "无特别局限",
                List.of(new ReportTaskSink.AnchorLine("986.50", 1, "VERIFIED", "get_quote",
                                Map.of("code", "600519"), NOW, "DATA", "现价986.50元"),
                        new ReportTaskSink.AnchorLine("8500亿", 1, "SOURCED", null, null, null, null, null)),
                List.of(new ReportTaskSink.AssumptionLine(1, "现价986.50元处于合理区间", 0),
                        new ReportTaskSink.AssumptionLine(2, "总市值8500亿量级", 1),
                        new ReportTaskSink.AssumptionLine(3, "防御：越界下标折未绑定", 99)));
    }

    // ———— 发起（F01）四分支 ————

    @Test
    @DisplayName("给定配额余量与本人项目，when startTask，then落 PENDING 任务并回 taskId+余量")
    void givenQuotaLeftAndOwnedProject_whenStartTask_thenPendingTaskWithRemaining() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project(USER)));
        when(taskRepository.save(any(ReportTask.class))).thenAnswer(inv -> ReportTask.reconstitute(
                TASK_ID, USER, PROJECT_ID, inv.getArgument(0, ReportTask.class).status(),
                ReportTask.Stage.COLLECTING, 0L, null, null, NOW, NOW, null, null));
        when(quotaService.remainingToday(USER)).thenReturn(400_000L);

        ReportApplicationService.StartTaskResult result = service.startTask(USER, PROJECT_ID);

        assertThat(result.taskId()).isEqualTo(TASK_ID);
        assertThat(result.quotaRemaining()).isEqualTo(400_000L);
        verify(quotaService).assertCanStart(USER);
        ArgumentCaptor<ReportTask> captor = ArgumentCaptor.forClass(ReportTask.class);
        verify(taskRepository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(ReportTask.Status.PENDING);
        assertThat(captor.getValue().projectId()).isEqualTo(PROJECT_ID);
        assertThat(captor.getValue().tokenUsage()).isZero();
    }

    @Test
    @DisplayName("给定当日配额已用尽，when startTask，then透传 402 语义异常且不查项目不落任务")
    void givenQuotaExhausted_whenStartTask_thenPropagatesWithoutSave() {
        doThrow(new ReportQuotaExceededException("当日研报 token 配额已用尽", 0L))
                .when(quotaService).assertCanStart(USER);

        assertThatThrownBy(() -> service.startTask(USER, PROJECT_ID))
                .isInstanceOf(ReportQuotaExceededException.class);

        verify(projectRepository, never()).findById(anyLong());
        verify(taskRepository, never()).save(any(ReportTask.class));
    }

    @Test
    @DisplayName("给定项目不存在（或属他人），when startTask，then NOT_FOUND 且不落任务")
    void givenForeignProject_whenStartTask_thenNotFound() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project(OTHER)));

        assertThatThrownBy(() -> service.startTask(USER, PROJECT_ID))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.NOT_FOUND));

        verify(taskRepository, never()).save(any(ReportTask.class));
    }

    @Test
    @DisplayName("给定同项目已有活跃任务（撞 uq_report_task_active），when startTask，then TASK_ACTIVE_EXISTS（409 语义）")
    void givenActiveTaskExists_whenStartTask_thenTaskActiveExists() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project(USER)));
        when(taskRepository.save(any(ReportTask.class)))
                .thenThrow(new DataIntegrityViolationException("uq_report_task_active"));

        assertThatThrownBy(() -> service.startTask(USER, PROJECT_ID))
                .isInstanceOfSatisfying(ResearchException.class, e -> {
                    assertThat(e.code()).isEqualTo(ResearchErrorCode.TASK_ACTIVE_EXISTS);
                    assertThat(e.getMessage()).contains("进行中");
                });
    }

    // ———— 轮询与列表 ————

    @Test
    @DisplayName("给定项目无任何任务，when latestTask，then NOT_FOUND")
    void givenNoTask_whenLatestTask_thenNotFound() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project(USER)));
        when(taskRepository.findLatestByProjectId(PROJECT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.latestTask(USER, PROJECT_ID))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("给定最新任务 SUCCEEDED 且研报已落，when latestTask，then透出终态与 reportId")
    void givenSucceededTaskWithReport_whenLatestTask_thenViewWithReportId() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project(USER)));
        when(taskRepository.findLatestByProjectId(PROJECT_ID))
                .thenReturn(Optional.of(task(ReportTask.Status.SUCCEEDED, 2500L, null, null)));
        when(reportRepository.findByTaskId(TASK_ID)).thenReturn(Optional.of(report(
                ResearchReport.Status.PENDING_CONFIRM, null, null)));

        TaskStatusView view = service.latestTask(USER, PROJECT_ID);

        assertThat(view.status()).isEqualTo("SUCCEEDED");
        assertThat(view.stage()).isEqualTo("VALIDATING");
        assertThat(view.tokenUsage()).isEqualTo(2500L);
        assertThat(view.errorCode()).isNull();
        assertThat(view.errorMessage()).isNull();
        assertThat(view.reportId()).isEqualTo(REPORT_ID);
    }

    @Test
    @DisplayName("给定最新任务 FAILED，when latestTask，then透出错误诊断且 reportId 空")
    void givenFailedTask_whenLatestTask_thenDiagnosticsWithoutReportId() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project(USER)));
        when(taskRepository.findLatestByProjectId(PROJECT_ID))
                .thenReturn(Optional.of(task(ReportTask.Status.FAILED, 900L, "VALIDATION_FAILED", "无工具数据支撑")));
        when(reportRepository.findByTaskId(TASK_ID)).thenReturn(Optional.empty());

        TaskStatusView view = service.latestTask(USER, PROJECT_ID);

        assertThat(view.status()).isEqualTo("FAILED");
        assertThat(view.errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(view.errorMessage()).contains("无工具数据支撑");
        assertThat(view.reportId()).isNull();
    }

    @Test
    @DisplayName("给定项目两份研报，when reports，then按仓库序映射摘要视图")
    void givenTwoReports_whenReports_thenSummaryList() {
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project(USER)));
        ResearchReport first = report(ResearchReport.Status.CONFIRMED, null, NOW);
        ResearchReport second = ResearchReport.reconstitute(10L, 16L, PROJECT_ID, USER,
                ResearchReport.Status.REJECTED, "二期研报", "二期正文", null, null, "数据单薄",
                NOW.minusSeconds(60), NOW.minusSeconds(60));
        when(reportRepository.findByProjectIdOrderByCreatedAtDesc(PROJECT_ID))
                .thenReturn(List.of(first, second));

        List<ReportSummaryView> views = service.reports(USER, PROJECT_ID);

        assertThat(views).extracting(ReportSummaryView::id).containsExactly(REPORT_ID, 10L);
        assertThat(views).extracting(ReportSummaryView::status)
                .containsExactly("CONFIRMED", "REJECTED");
        assertThat(views).extracting(ReportSummaryView::title)
                .containsExactly("茅台深度研报", "二期研报");
    }

    // ———— 详情（F02：staleness 注入）————

    @Test
    @DisplayName("给定研报属他人，when reportDetail，then NOT_FOUND 且不触发重查")
    void givenForeignReport_whenReportDetail_thenNotFoundWithoutRecheck() {
        when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(ResearchReport.reconstitute(
                REPORT_ID, TASK_ID, PROJECT_ID, OTHER, ResearchReport.Status.PENDING_CONFIRM,
                "他人研报", "正文", null, null, null, NOW, NOW)));

        assertThatThrownBy(() -> service.reportDetail(USER, REPORT_ID))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.NOT_FOUND));
        verify(stalenessService, never()).statusesFor(anyLong(), anyLong());
    }

    @Test
    @DisplayName("给定已落锚点与绑定假设，when reportDetail，then假设带锚点视图与重查四态、stalenessCheckedAt 取时钟")
    void givenAnchorsAndBoundAssumptions_whenReportDetail_thenAssumptionsCarryAnchorStates() {
        when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report(
                ResearchReport.Status.PENDING_CONFIRM, null, null)));
        when(anchorRepository.findByReportId(REPORT_ID)).thenReturn(List.of(
                persistedAnchor(101L, "986.50", "VERIFIED", "get_quote"),
                persistedAnchor(102L, "8500亿", "SOURCED", null)));
        when(assumptionRepository.findByReportId(REPORT_ID)).thenReturn(List.of(
                new ReportAssumption(1L, REPORT_ID, 1, "现价986.50元处于合理区间", 101L),
                new ReportAssumption(2L, REPORT_ID, 2, "总市值8500亿量级", 102L)));
        when(stalenessService.statusesFor(USER, REPORT_ID)).thenReturn(List.of(
                new ReportStalenessService.AnchorStatus(101L, ReportStalenessService.AnchorState.FRESH, "986.50"),
                new ReportStalenessService.AnchorStatus(102L, ReportStalenessService.AnchorState.CHANGED, null)));

        ReportDetailView detail = service.reportDetail(USER, REPORT_ID);

        assertThat(detail.id()).isEqualTo(REPORT_ID);
        assertThat(detail.status()).isEqualTo("PENDING_CONFIRM");
        assertThat(detail.contentMd()).contains("986.50");
        assertThat(detail.limitations()).isEqualTo("行情接口部分失败");
        assertThat(detail.stalenessCheckedAt()).isEqualTo(NOW);
        assertThat(detail.assumptions()).hasSize(2);
        ReportDetailView.AssumptionView first = detail.assumptions().get(0);
        assertThat(first.seq()).isEqualTo(1);
        assertThat(first.anchor().tool()).isEqualTo("get_quote");
        assertThat(first.anchor().args()).isEqualTo(Map.of("code", "600519"));
        assertThat(first.anchor().asOf()).isEqualTo(NOW);
        assertThat(first.anchor().state()).isEqualTo("FRESH");
        assertThat(first.anchor().currentValue()).isEqualTo("986.50");
        ReportDetailView.AssumptionView second = detail.assumptions().get(1);
        assertThat(second.anchor().tool()).isNull();
        assertThat(second.anchor().state()).isEqualTo("CHANGED");
        assertThat(second.anchor().currentValue()).isNull();
    }

    @Test
    @DisplayName("给定门未绑定假设（anchorId 空），when reportDetail，then该条 anchor 视图为 null")
    void givenUnboundAssumption_whenReportDetail_thenAnchorViewNull() {
        when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report(
                ResearchReport.Status.CONFIRMED, null, NOW)));
        when(anchorRepository.findByReportId(REPORT_ID)).thenReturn(List.of());
        when(assumptionRepository.findByReportId(REPORT_ID)).thenReturn(List.of(
                new ReportAssumption(1L, REPORT_ID, 1, "防御性未绑定条目", null)));
        when(stalenessService.statusesFor(USER, REPORT_ID)).thenReturn(List.of());

        ReportDetailView detail = service.reportDetail(USER, REPORT_ID);

        assertThat(detail.assumptions()).singleElement()
                .satisfies(a -> assertThat(a.anchor()).isNull());
    }

    // ———— 确认状态机（§3.10）————

    @Test
    @DisplayName("给定待确认研报，when confirm，then CAS 迁移落 confirmedAt 并写 RESEARCH_EVENT「研报定稿」事件")
    void givenPendingReport_whenConfirm_thenConfirmedWithJournalEvent() {
        when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report(
                ResearchReport.Status.PENDING_CONFIRM, null, null)))
                .thenReturn(Optional.of(report(ResearchReport.Status.CONFIRMED, null, NOW)));
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project(USER)));
        when(reportRepository.confirmIfPending(REPORT_ID, NOW, NOW)).thenReturn(1);

        ReportSummaryView view = service.confirm(USER, REPORT_ID);

        assertThat(view.status()).isEqualTo("CONFIRMED");
        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalRepository).save(captor.capture());
        JournalEntry event = captor.getValue();
        assertThat(event.type()).isEqualTo(com.portfolio.invest.domain.journal.JournalEntryType.RESEARCH_EVENT);
        assertThat(event.title()).isEqualTo("研报定稿");
        assertThat(event.projectId()).isEqualTo(PROJECT_ID);
        assertThat(event.stockCode()).isEqualTo("600519");
        assertThat(event.stockName()).isEqualTo("贵州茅台");
        assertThat(event.eventDate()).isEqualTo(LocalDate.of(2026, 10, 11));
        assertThat(event.content()).contains("茅台深度研报");
    }

    @Test
    @DisplayName("给定已确认研报再 confirm（终态），when confirm，then REPORT_INVALID_STATE 且不 CAS 不写事件")
    void givenConfirmedReport_whenConfirmAgain_thenInvalidState() {
        when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report(
                ResearchReport.Status.CONFIRMED, null, NOW)));

        assertThatThrownBy(() -> service.confirm(USER, REPORT_ID))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.REPORT_INVALID_STATE));
        verify(reportRepository, never()).confirmIfPending(anyLong(), any(), any());
        verify(journalRepository, never()).save(any());
    }

    @Test
    @DisplayName("给定并发对手已先迁移（CAS 返 0），when confirm，then REPORT_INVALID_STATE 且不写事件")
    void givenConcurrentWinnerExists_whenConfirm_thenInvalidStateWithoutJournal() {
        when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report(
                ResearchReport.Status.PENDING_CONFIRM, null, null)));
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project(USER)));
        when(reportRepository.confirmIfPending(REPORT_ID, NOW, NOW)).thenReturn(0);

        assertThatThrownBy(() -> service.confirm(USER, REPORT_ID))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.REPORT_INVALID_STATE));
        verify(journalRepository, never()).save(any());
    }

    @Test
    @DisplayName("给定待确认研报，when reject 携理由，then CAS 迁移 REJECTED 留痕且不写事件")
    void givenPendingReport_whenRejectWithReason_thenRejectedWithTrace() {
        when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report(
                ResearchReport.Status.PENDING_CONFIRM, null, null)))
                .thenReturn(Optional.of(report(ResearchReport.Status.REJECTED, "估值依据不足", null)));
        when(reportRepository.rejectIfPending(REPORT_ID, "估值依据不足", NOW)).thenReturn(1);

        ReportSummaryView view = service.reject(USER, REPORT_ID, "估值依据不足");

        assertThat(view.status()).isEqualTo("REJECTED");
        verify(journalRepository, never()).save(any());
    }

    @Test
    @DisplayName("给定终态研报 reject（理由合规），when reject，then REPORT_INVALID_STATE")
    void givenTerminalReport_whenRejectWithReason_thenInvalidState() {
        when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report(
                ResearchReport.Status.CONFIRMED, null, NOW)));

        assertThatThrownBy(() -> service.reject(USER, REPORT_ID, "不要了"))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.REPORT_INVALID_STATE));
        verify(reportRepository, never()).rejectIfPending(anyLong(), any(), any());
    }

    @Test
    @DisplayName("给定终态研报且 reason 空白，when reject，then REJECT_REASON_REQUIRED（400 优先于 409）")
    void givenTerminalReportAndBlankReason_whenReject_thenReasonRequiredFirst() {
        when(reportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report(
                ResearchReport.Status.CONFIRMED, null, NOW)));

        assertThatThrownBy(() -> service.reject(USER, REPORT_ID, "  "))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.REJECT_REASON_REQUIRED));
        verify(reportRepository, never()).rejectIfPending(anyLong(), any(), any());
    }

    // ———— sink 回调（Ruling-15/17/7）————

    @Test
    @DisplayName("给定任务已 SUCCEEDED，when onTaskSucceeded，then研报/锚点/假设落库 + settle（不碰任务行）")
    void givenSucceededTask_whenOnTaskSucceeded_thenPersistsReportAnchorsAssumptionsAndSettles() {
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task(
                ReportTask.Status.SUCCEEDED, 2500L, null, null)));
        when(reportRepository.findByTaskId(TASK_ID)).thenReturn(Optional.empty());
        when(reportRepository.save(any(ResearchReport.class))).thenAnswer(inv -> ResearchReport.reconstitute(
                REPORT_ID, TASK_ID, PROJECT_ID, USER, ResearchReport.Status.PENDING_CONFIRM,
                "茅台深度研报", "正文", null, null, null, NOW, NOW));
        when(anchorRepository.findByReportId(REPORT_ID)).thenReturn(List.of(
                persistedAnchor(101L, "986.50", "VERIFIED", "get_quote"),
                persistedAnchor(102L, "8500亿", "SOURCED", null)));

        service.onTaskSucceeded(TASK_ID, 2500L, payload());

        ArgumentCaptor<ResearchReport> reportCaptor = ArgumentCaptor.forClass(ResearchReport.class);
        verify(reportRepository).save(reportCaptor.capture());
        assertThat(reportCaptor.getValue().taskId()).isEqualTo(TASK_ID);
        assertThat(reportCaptor.getValue().status()).isEqualTo(ResearchReport.Status.PENDING_CONFIRM);
        ArgumentCaptor<List<ReportAnchor>> anchorCaptor = ArgumentCaptor.captor();
        verify(anchorRepository).saveAll(eq(REPORT_ID), anchorCaptor.capture());
        assertThat(anchorCaptor.getValue()).hasSize(2);
        assertThat(anchorCaptor.getValue().get(0).snippet()).isEqualTo("986.50");
        assertThat(anchorCaptor.getValue().get(0).state()).isEqualTo("VERIFIED");
        assertThat(anchorCaptor.getValue().get(0).asOf()).isEqualTo(NOW);
        assertThat(anchorCaptor.getValue().get(1).toolName()).isNull();
        ArgumentCaptor<List<ReportAssumption>> assumptionCaptor = ArgumentCaptor.captor();
        verify(assumptionRepository).saveAll(eq(REPORT_ID), assumptionCaptor.capture());
        assertThat(assumptionCaptor.getValue()).extracting(ReportAssumption::seq).containsExactly(1, 2, 3);
        assertThat(assumptionCaptor.getValue()).extracting(ReportAssumption::anchorId)
                .containsExactly(101L, 102L, null); // 越界下标防御性折未绑定
        verify(quotaService).settle(USER, 2500L);
        verify(taskRepository, never()).save(any(ReportTask.class)); // Ruling-15：不迁移任务行
    }

    @Test
    @DisplayName("给定该任务研报已存在（崩溃重放），when onTaskSucceeded，then整体 no-op 不重复落库不结算")
    void givenReportAlreadyExists_whenOnTaskSucceeded_thenNoOp() {
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task(
                ReportTask.Status.SUCCEEDED, 2500L, null, null)));
        when(reportRepository.findByTaskId(TASK_ID)).thenReturn(Optional.of(report(
                ResearchReport.Status.PENDING_CONFIRM, null, null)));

        service.onTaskSucceeded(TASK_ID, 2500L, payload());

        verify(reportRepository, never()).save(any(ResearchReport.class));
        verify(anchorRepository, never()).saveAll(anyLong(), anyList());
        verify(assumptionRepository, never()).saveAll(anyLong(), anyList());
        verify(quotaService, never()).settle(anyLong(), anyLong());
    }

    @Test
    @DisplayName("给定任务已 FAILED，when onTaskFailed，then仅 settle 配额（任务行不动）")
    void givenFailedTask_whenOnTaskFailed_thenSettlesOnly() {
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task(
                ReportTask.Status.FAILED, 900L, "VALIDATION_FAILED", "无工具数据支撑")));

        service.onTaskFailed(TASK_ID, 900L, "VALIDATION_FAILED", "无工具数据支撑");

        verify(quotaService).settle(USER, 900L);
        verify(taskRepository, never()).save(any(ReportTask.class));
        verify(reportRepository, never()).save(any(ResearchReport.class));
    }

    @Test
    @DisplayName("给定任务行缺失（契约违反），when onTaskFailed，then NOT_FOUND 快速失败")
    void givenMissingTaskRow_whenOnTaskFailed_thenNotFound() {
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.onTaskFailed(TASK_ID, 0L, "AGENT_ERROR", "x"))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.NOT_FOUND));
    }
}
