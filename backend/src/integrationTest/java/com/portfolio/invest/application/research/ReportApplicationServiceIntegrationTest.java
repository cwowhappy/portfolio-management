package com.portfolio.invest.application.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.invest.application.research.ResearchViews.ReportDetailView;
import com.portfolio.invest.application.research.ResearchViews.ReportSummaryView;
import com.portfolio.invest.application.research.ResearchViews.TaskStatusView;
import com.portfolio.invest.domain.research.ReportAnchor;
import com.portfolio.invest.domain.research.ReportAnchorRepository;
import com.portfolio.invest.domain.research.ReportAssumption;
import com.portfolio.invest.domain.research.ReportAssumptionRepository;
import com.portfolio.invest.domain.research.ReportTask;
import com.portfolio.invest.domain.research.ReportTaskRepository;
import com.portfolio.invest.domain.research.ResearchErrorCode;
import com.portfolio.invest.domain.research.ResearchException;
import com.portfolio.invest.domain.research.ResearchReport;
import com.portfolio.invest.domain.research.ResearchReportRepository;
import com.portfolio.invest.support.PostgresTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 研报应用服务集成（MS-32 B11，真 PG 全上下文）：sink 全链落库（SucceededPayload →
 * 研报 PENDING_CONFIRM/锚点 JSONB/假设按 anchorIndex 绑定 + settle 行）与 Ruling-7 重放
 * no-op；发起四分支（正常/活跃冲突 TASK_ACTIVE_EXISTS/终态释放后再发起/配额 402 语义/
 * 归属 404）；确认状态机 CAS（confirm 写 journal 事件、reject 留痕、并发双 confirm 恰一成功、
 * 终态+空白 reason → 400 优先序）；启动恢复 Runner（PENDING/RUNNING → FAILED）；
 * 详情 staleness 注入（白名单外工具离线收敛 UNVERIFIABLE，不打真行情）。
 */
@SpringBootTest(properties = {
        "invest.report.quota.daily-token-budget=1000",       // 钉死配额口径（默认 500k 不受 .env 波动）
        "invest.mcp.harness.state-root=build/report-app-it/state"})
class ReportApplicationServiceIntegrationTest extends PostgresTestSupport {

    private static final Instant NOW = Instant.parse("2026-10-11T04:00:00Z");

    @Autowired
    private ReportApplicationService service;

    @Autowired
    private ReportTaskRecoveryRunner recoveryRunner;

    @Autowired
    private ReportTaskRepository taskRepository;

    @Autowired
    private ResearchReportRepository reportRepository;

    @Autowired
    private ReportAnchorRepository anchorRepository;

    @Autowired
    private ReportAssumptionRepository assumptionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long seedUser() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO app_user(username, password_hash, role, status) "
                        + "VALUES (?, 'x', 'USER', 'APPROVED') RETURNING id",
                Long.class, "report_b11_" + System.nanoTime());
    }

    private Long seedProject(Long userId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO research_project(user_id, stock_code, stock_name, title, current_stage) "
                        + "VALUES (?, '600519', '贵州茅台', 'B11集成研究', 'NEW_ANALYSIS') RETURNING id",
                Long.class, userId);
    }

    /** 上海自然日当日行（与 ReportTokenQuotaService 内部 clock 同口径，防 UTC/上海跨日错位）。 */
    private Long quotaUsed(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT tokens_used FROM report_token_quota WHERE user_id = ? "
                        + "AND quota_date = (now() AT TIME ZONE 'Asia/Shanghai')::date",
                Long.class, userId);
    }

    private ReportTask seedSucceededTask(Long userId, Long projectId, long tokens) {
        ReportTask task = taskRepository.save(ReportTask.create(userId, projectId, NOW));
        task = taskRepository.save(task.markRunning(NOW));
        return taskRepository.save(task.succeed(tokens, NOW));
    }

    private static ReportTaskSink.SucceededPayload payload() {
        return new ReportTaskSink.SucceededPayload("茅台深度研报（B11）",
                "现价986.50元，总市值8500亿。\n\n## 关键假设\n1. 现价986.50元处于合理区间\n2. 总市值8500亿量级",
                "行情接口部分失败",
                List.of(new ReportTaskSink.AnchorLine("986.50", 1, "VERIFIED", "get_quote",
                                Map.of("code", "600519"), NOW, "DATA", "现价986.50元"),
                        new ReportTaskSink.AnchorLine("8500亿", 1, "SOURCED", null, null, null, null, null)),
                List.of(new ReportTaskSink.AssumptionLine(1, "现价986.50元处于合理区间", 0),
                        new ReportTaskSink.AssumptionLine(2, "总市值8500亿量级", 1)));
    }

    @Test
    @DisplayName("给定 SUCCEEDED 任务行，when onTaskSucceeded，then研报/锚点/假设/settle 全链落库")
    void givenSucceededTask_whenOnTaskSucceeded_thenFullChainPersisted() {
        Long user = seedUser();
        Long project = seedProject(user);
        ReportTask task = seedSucceededTask(user, project, 2500L);

        service.onTaskSucceeded(task.id(), 2500L, payload());

        ResearchReport report = reportRepository.findByTaskId(task.id()).orElseThrow();
        assertThat(report.status()).isEqualTo(ResearchReport.Status.PENDING_CONFIRM);
        assertThat(report.title()).isEqualTo("茅台深度研报（B11）");
        assertThat(report.contentMd()).contains("986.50");
        assertThat(report.limitations()).isEqualTo("行情接口部分失败");
        List<ReportAnchor> anchors = anchorRepository.findByReportId(report.id());
        assertThat(anchors).hasSize(2);
        assertThat(anchors.get(0).state()).isEqualTo("VERIFIED");
        assertThat(anchors.get(0).toolName()).isEqualTo("get_quote");
        assertThat(anchors.get(0).args()).isEqualTo(Map.of("code", "600519")); // JSONB 往返保真
        assertThat(anchors.get(0).asOf()).isEqualTo(NOW);
        assertThat(anchors.get(0).asOfKind()).isEqualTo("DATA");
        assertThat(anchors.get(1).toolName()).isNull();
        List<ReportAssumption> assumptions = assumptionRepository.findByReportId(report.id());
        assertThat(assumptions).extracting(ReportAssumption::seq).containsExactly(1, 2);
        assertThat(assumptions).extracting(ReportAssumption::anchorId)
                .containsExactly(anchors.get(0).id(), anchors.get(1).id()); // anchorIndex → 落库 id
        assertThat(quotaUsed(user)).isEqualTo(2500L);

        // Ruling-7：崩溃重放整体 no-op——不双份研报/锚点/假设，不重复结算
        service.onTaskSucceeded(task.id(), 2500L, payload());
        assertThat(reportRepository.findByProjectIdOrderByCreatedAtDesc(project)).hasSize(1);
        assertThat(anchorRepository.findByReportId(report.id())).hasSize(2);
        assertThat(assumptionRepository.findByReportId(report.id())).hasSize(2);
        assertThat(quotaUsed(user)).isEqualTo(2500L);
    }

    @Test
    @DisplayName("给定 FAILED 任务行，when onTaskFailed，then仅 settle（任务行不被触碰）")
    void givenFailedTask_whenOnTaskFailed_thenSettlesOnly() {
        Long user = seedUser();
        Long project = seedProject(user);
        ReportTask task = taskRepository.save(ReportTask.create(user, project, NOW)
                .markRunning(NOW).fail("VALIDATION_FAILED", "无工具数据支撑", 900L, NOW));

        service.onTaskFailed(task.id(), 900L, "VALIDATION_FAILED", "无工具数据支撑");

        assertThat(quotaUsed(user)).isEqualTo(900L);
        assertThat(taskRepository.findById(task.id()).orElseThrow().errorCode())
                .isEqualTo("VALIDATION_FAILED"); // Ruling-15：任务行诊断保持编排器原值
        assertThat(reportRepository.findByTaskId(task.id())).isEmpty();
    }

    @Test
    @DisplayName("给定余量充足，when startTask，then PENDING 行 + 余量返回；再发起撞活跃索引 TASK_ACTIVE_EXISTS；终态后可再发起")
    void givenFreshUser_whenStartTaskTwice_thenSecondRejectedUntilTerminal() {
        Long user = seedUser();
        Long project = seedProject(user);

        ReportApplicationService.StartTaskResult first = service.startTask(user, project);
        assertThat(first.taskId()).isNotNull();
        assertThat(first.quotaRemaining()).isEqualTo(1000L);
        assertThat(taskRepository.findById(first.taskId()).orElseThrow().status())
                .isEqualTo(ReportTask.Status.PENDING);

        assertThatThrownBy(() -> service.startTask(user, project))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.TASK_ACTIVE_EXISTS));

        ReportTask latest = taskRepository.findLatestByProjectId(project).orElseThrow();
        taskRepository.save(latest.fail("AGENT_ERROR", "终态释放槽位", 0L, NOW));
        ReportApplicationService.StartTaskResult retry = service.startTask(user, project);
        assertThat(retry.taskId()).isNotEqualTo(first.taskId());
    }

    @Test
    @DisplayName("给定当日配额已耗尽（used==budget），when startTask，then 402 语义异常且不落任务行")
    void givenQuotaExhausted_whenStartTask_thenRejectedWithoutRow() {
        Long user = seedUser();
        Long project = seedProject(user);
        jdbcTemplate.update("INSERT INTO report_token_quota(user_id, quota_date, tokens_used) "
                + "VALUES (?, (now() AT TIME ZONE 'Asia/Shanghai')::date, 1000)", user);

        assertThatThrownBy(() -> service.startTask(user, project))
                .isInstanceOf(ReportQuotaExceededException.class);
        assertThat(taskRepository.findLatestByProjectId(project)).isEmpty();
    }

    @Test
    @DisplayName("给定项目属他人，when startTask，then NOT_FOUND")
    void givenForeignProject_whenStartTask_thenNotFound() {
        Long owner = seedUser();
        Long project = seedProject(owner);
        Long other = seedUser();

        assertThatThrownBy(() -> service.startTask(other, project))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("给定待确认研报，when confirm，then CONFIRMED + journal「研报定稿」事件；再 confirm → 409 语义")
    void givenPendingReport_whenConfirm_thenConfirmedWithJournalEvent() {
        Long user = seedUser();
        Long project = seedProject(user);
        ReportTask task = seedSucceededTask(user, project, 100L);
        ResearchReport report = reportRepository.save(ResearchReport.create(task.id(), project,
                user, "确认链路研报", "正文含 986.50。", null, NOW));

        ReportSummaryView view = service.confirm(user, report.id());

        assertThat(view.status()).isEqualTo("CONFIRMED");
        ResearchReport reloaded = reportRepository.findById(report.id()).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(ResearchReport.Status.CONFIRMED);
        assertThat(reloaded.confirmedAt()).isNotNull();
        Integer events = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM journal_entry WHERE project_id = ? AND type = 'RESEARCH_EVENT' "
                        + "AND title = '研报定稿'", Integer.class, project);
        assertThat(events).isEqualTo(1);

        assertThatThrownBy(() -> service.confirm(user, report.id()))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.REPORT_INVALID_STATE));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM journal_entry WHERE project_id = ? AND title = '研报定稿'",
                Integer.class, project)).isEqualTo(1); // 终态再操作不重复写事件
    }

    @Test
    @DisplayName("给定待确认研报，when reject 携理由，then REJECTED 留痕；终态+空白 reason → 400 优先序")
    void givenPendingReport_whenReject_thenRejectedWithReason() {
        Long user = seedUser();
        Long project = seedProject(user);
        ReportTask task = seedSucceededTask(user, project, 100L);
        ResearchReport report = reportRepository.save(ResearchReport.create(task.id(), project,
                user, "拒绝链路研报", "正文。", null, NOW));

        ReportSummaryView view = service.reject(user, report.id(), "估值依据不足");

        assertThat(view.status()).isEqualTo("REJECTED");
        assertThat(reportRepository.findById(report.id()).orElseThrow().rejectedReason())
                .isEqualTo("估值依据不足");

        assertThatThrownBy(() -> service.reject(user, report.id(), "  "))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.REJECT_REASON_REQUIRED));
    }

    @Test
    @DisplayName("给定两线程并发 confirm 同一研报，when 双提交，then CAS 恰一成功、另一笔 409、事件恰一条")
    void givenTwoConcurrentConfirms_whenBothSubmit_thenExactlyOneSucceeds() throws Exception {
        Long user = seedUser();
        Long project = seedProject(user);
        ReportTask task = seedSucceededTask(user, project, 100L);
        ResearchReport report = reportRepository.save(ResearchReport.create(task.id(), project,
                user, "并发确认研报", "正文。", null, NOW));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger successes = new AtomicInteger();
        try {
            CountDownLatch startGate = new CountDownLatch(1);
            Future<Boolean> first = pool.submit(() -> {
                startGate.await();
                try {
                    service.confirm(user, report.id());
                    return true;
                } catch (ResearchException e) {
                    return false;
                }
            });
            Future<Boolean> second = pool.submit(() -> {
                startGate.await();
                try {
                    service.confirm(user, report.id());
                    return true;
                } catch (ResearchException e) {
                    return false;
                }
            });
            startGate.countDown();
            List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS))
                    .forEach(outcome -> {
                        if (outcome) {
                            successes.incrementAndGet();
                        }
                    });

            assertThat(successes.get()).as("并发双 confirm 恰一成功（§3.10 CAS）").isEqualTo(1);
            assertThat(reportRepository.findById(report.id()).orElseThrow().status())
                    .isEqualTo(ResearchReport.Status.CONFIRMED);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM journal_entry WHERE project_id = ? AND title = '研报定稿'",
                    Integer.class, project)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("given SUCCEEDED 任务与研报，when latestTask/reports/reportDetail，then轮询回连 reportId、列表倒序、离线重查 UNVERIFIABLE")
    void givenSucceededTaskAndReport_whenReads_thenViewsAndOfflineStaleness() {
        Long user = seedUser();
        Long project = seedProject(user);
        ReportTask task = seedSucceededTask(user, project, 2500L);
        service.onTaskSucceeded(task.id(), 2500L, payload());

        TaskStatusView status = service.latestTask(user, project);
        assertThat(status.status()).isEqualTo("SUCCEEDED");
        assertThat(status.tokenUsage()).isEqualTo(2500L);
        assertThat(status.reportId()).isNotNull();
        assertThat(service.reports(user, project)).singleElement()
                .satisfies(summary -> assertThat(summary.status()).isEqualTo("PENDING_CONFIRM"));

        // 详情：白名单外工具离线收敛 UNVERIFIABLE（不打真行情接口）
        ResearchReport report = reportRepository.findByTaskId(task.id()).orElseThrow();
        jdbcTemplate.update("UPDATE research_report_anchor SET tool_name = 'fetch_stock_quote' "
                + "WHERE report_id = ?", report.id());
        ReportDetailView detail = service.reportDetail(user, report.id());
        assertThat(detail.assumptions()).allSatisfy(a -> {
            assertThat(a.anchor()).isNotNull();
            assertThat(a.anchor().state()).isEqualTo("UNVERIFIABLE");
        });
        assertThat(detail.stalenessCheckedAt()).isNotNull();

        Long other = seedUser();
        assertThatThrownBy(() -> service.reportDetail(other, report.id()))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("given PENDING 与 RUNNING 悬挂任务，when 启动恢复，then均 FAILED(AGENT_ERROR) 且活跃清空、tokenUsage 保留")
    void givenSuspendedTasks_whenRecoveryRun_thenAllFailedAndActiveDrained() {
        Long user = seedUser();
        Long projectP = seedProject(user);
        Long projectQ = seedProject(user);
        ReportTask pending = taskRepository.save(ReportTask.create(user, projectP, NOW));
        ReportTask running = taskRepository.save(ReportTask.create(user, projectQ, NOW)
                .markRunning(NOW));
        taskRepository.save(running.markStage(ReportTask.Stage.ANALYZING, NOW));
        // tokenUsage 中间态：编排器未及回写时行上仍为 0——恢复后原值保留
        jdbcTemplate.update("UPDATE research_report_task SET token_usage = 666 WHERE id = ?",
                pending.id());

        recoveryRunner.run(null);

        Optional<ReportTask> recoveredPending = taskRepository.findById(pending.id());
        assertThat(recoveredPending.orElseThrow().status()).isEqualTo(ReportTask.Status.FAILED);
        assertThat(recoveredPending.orElseThrow().errorCode()).isEqualTo("AGENT_ERROR");
        assertThat(recoveredPending.orElseThrow().errorMessage()).contains("进程重启");
        assertThat(recoveredPending.orElseThrow().tokenUsage()).isEqualTo(666L);
        assertThat(taskRepository.findById(running.id()).orElseThrow().status())
                .isEqualTo(ReportTask.Status.FAILED);
        assertThat(taskRepository.findActiveAll())
                .noneMatch(t -> t.projectId().equals(projectP)
                        || t.projectId().equals(projectQ)); // 活跃清空（幂等：再跑零写）
    }
}
