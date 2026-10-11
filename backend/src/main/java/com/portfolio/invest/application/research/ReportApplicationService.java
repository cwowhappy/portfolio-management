package com.portfolio.invest.application.research;

import com.portfolio.invest.application.research.ResearchViews.ReportDetailView;
import com.portfolio.invest.application.research.ResearchViews.ReportDetailView.AnchorView;
import com.portfolio.invest.application.research.ResearchViews.ReportDetailView.AssumptionView;
import com.portfolio.invest.application.research.ResearchViews.ReportSummaryView;
import com.portfolio.invest.application.research.ResearchViews.TaskStatusView;
import com.portfolio.invest.domain.journal.JournalEntry;
import com.portfolio.invest.domain.journal.JournalEntryRepository;
import com.portfolio.invest.domain.journal.JournalEntryType;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 研报任务应用服务（MS-32 B11，设计规格 §3.2/§3.9/§3.10）：任务生命周期（发起/轮询/列表）
 * + 确认状态机（CONFIRM/REJECT）+ 详情（staleness 注入）+ {@link ReportTaskSink} 落库实现
 * （B9 编排器终态回调）。执行编排（agent 侧 {@code ReportWorkflowOrchestrator.submit}）由
 * web 层（B13）在 startTask 成功后另行触发——本服务只管任务行与研报产物，不依赖 agent。
 *
 * <p><strong>发起（F01）：</strong>配额软检查（{@link ReportTokenQuotaService#assertCanStart}，
 * 402 语义异常）→ 项目归属校验（404）→ {@link ReportTask#create} + save 直插——「同项目至多
 * 一个活跃任务」由 V8 部分唯一索引 uq_report_task_active 兜底（TOCTOU 防御：不做
 * check-then-act，撞索引的 DataIntegrityViolationException 捕获映射 TASK_ACTIVE_EXISTS 409 语义）。</p>
 *
 * <p><strong>确认状态机（§3.10）：</strong>经 findById→域方法 Optional 判空（终态再操作 →
 * REPORT_INVALID_STATE 409 语义；域 {@code reject} 的 reason 校验先于状态检查——终态+空白
 * reason → REJECT_REASON_REQUIRED 400 而非 409）→ 条件 UPDATE（WHERE status='PENDING_CONFIRM'）
 * 落库。research_report 无 version 列（B3 核实，不加 @Version），并发双击以状态行 CAS 争用
 * 判定恰一成功（设计规格 §3.10 裁定形态）——CAS 返 0 = 对手已迁移 → 409。CONFIRM 成功后写
 * journal RESEARCH_EVENT「研报定稿」事件（沿 {@code ResearchApplicationService.writeEvent}
 * A21 模式，同事务）。</p>
 *
 * <p><strong>sink 回调（Ruling-15/17/7）：</strong>回调前任务行已由编排器置终态——本实现
 * <b>不迁移任务行</b>（succeed/fail 对非 RUNNING/PENDING 会抛，重复迁移违反回调契约）；
 * onTaskSucceeded = 研报（PENDING_CONFIRM）/锚点/假设落库 + 配额结算，onTaskFailed = 仅配额
 * 结算；两方法挂 {@code @Transactional}（产物原子落库 + 为 B3 settle 的 {@code @Modifying}
 * 提供活动事务）。幂等（Ruling-7）：研报表按 task_id 查重，已存在则整个回调 no-op 返回
 * （防崩溃重放双份研报——重放窗口内结算已随首调事务原子提交/回滚，不存在「研报在而结算不在」；
 * 极端场景下 settle 双调的累计偏差偏保守无害）。</p>
 */
@Service
public class ReportApplicationService implements ReportTaskSink {

    private static final Logger log = LoggerFactory.getLogger(ReportApplicationService.class);

    /** 业务时区（journal 事件 eventDate 沿 ResearchApplicationService 同 zone 口径）。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final ReportTaskRepository taskRepository;
    private final ResearchReportRepository reportRepository;
    private final ReportAnchorRepository anchorRepository;
    private final ReportAssumptionRepository assumptionRepository;
    private final ResearchProjectRepository projectRepository;
    private final ReportTokenQuotaService quotaService;
    private final ReportStalenessService stalenessService;
    private final JournalEntryRepository journalRepository;
    private final Clock clock;

    /** 发起结果（§3.12 POST 202 体）：taskId + 当日配额余量（前端提示条）。 */
    public record StartTaskResult(Long taskId, long quotaRemaining) {}

    /** 主构造器（@Autowired：存在测试专用重载构造器时需显式指定注入入口）。 */
    @Autowired
    public ReportApplicationService(ReportTaskRepository taskRepository,
                                    ResearchReportRepository reportRepository,
                                    ReportAnchorRepository anchorRepository,
                                    ReportAssumptionRepository assumptionRepository,
                                    ResearchProjectRepository projectRepository,
                                    ReportTokenQuotaService quotaService,
                                    ReportStalenessService stalenessService,
                                    JournalEntryRepository journalRepository) {
        this(taskRepository, reportRepository, anchorRepository, assumptionRepository,
                projectRepository, quotaService, stalenessService, journalRepository,
                Clock.system(ZONE));
    }

    /** 测试构造器：注入时钟（事件打点/确认时刻/CAS 时间戳确定性断言，沿 B4 双构造器先例）。 */
    ReportApplicationService(ReportTaskRepository taskRepository,
                             ResearchReportRepository reportRepository,
                             ReportAnchorRepository anchorRepository,
                             ReportAssumptionRepository assumptionRepository,
                             ResearchProjectRepository projectRepository,
                             ReportTokenQuotaService quotaService,
                             ReportStalenessService stalenessService,
                             JournalEntryRepository journalRepository, Clock clock) {
        this.taskRepository = taskRepository;
        this.reportRepository = reportRepository;
        this.anchorRepository = anchorRepository;
        this.assumptionRepository = assumptionRepository;
        this.projectRepository = projectRepository;
        this.quotaService = quotaService;
        this.stalenessService = stalenessService;
        this.journalRepository = journalRepository;
        this.clock = clock;
    }

    /**
     * 发起（F01）：配额软检查 → 项目归属校验 → 新建 PENDING 任务直插（撞活跃索引 → 409 语义）。
     * 不在此触发执行——编排器 submit 由 web 层（B13）拿 taskId 后调用。
     */
    public StartTaskResult startTask(Long userId, Long projectId) {
        quotaService.assertCanStart(userId);
        requireProject(userId, projectId);
        ReportTask task;
        try {
            task = taskRepository.save(ReportTask.create(userId, projectId, clock.instant()));
        } catch (DataIntegrityViolationException e) {
            // uq_report_task_active：并发双发起第二个直插必撞（B9 Minor-2，不做 check-then-act）
            throw new ResearchException(ResearchErrorCode.TASK_ACTIVE_EXISTS,
                    "该项目已有进行中的研报任务，请待其完成或失败后重试");
        }
        return new StartTaskResult(task.id(), quotaService.remainingToday(userId));
    }

    /** 轮询（§3.12）：项目最新任务；无任务 NOT_FOUND。SUCCEEDED 任务的 reportId 经 task_id 回连。 */
    public TaskStatusView latestTask(Long userId, Long projectId) {
        requireProject(userId, projectId);
        ReportTask task = taskRepository.findLatestByProjectId(projectId)
                .orElseThrow(() -> new ResearchException(ResearchErrorCode.NOT_FOUND, "研报任务不存在"));
        Long reportId = reportRepository.findByTaskId(task.id()).map(ResearchReport::id).orElse(null);
        return TaskStatusView.from(task, reportId);
    }

    /** 项目研报列表（createdAt 倒序，仓库保证）。 */
    public List<ReportSummaryView> reports(Long userId, Long projectId) {
        requireProject(userId, projectId);
        return reportRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(ReportSummaryView::from)
                .toList();
    }

    /**
     * 详情（F02）：归属校验（缓存键无 userId——须先校验再触重查，B12 契约）→ 锚点/假设读取
     * → staleness 四态按 anchorId 注入。stalenessCheckedAt 为本次取数时刻（TTL 缓存命中时
     * 实际重查最早可追溯至 recheck-ttl 前，见 {@link ReportDetailView} javadoc）。
     */
    public ReportDetailView reportDetail(Long userId, Long reportId) {
        ResearchReport report = requireReport(userId, reportId);
        Map<Long, ReportStalenessService.AnchorStatus> statuses = stalenessService
                .statusesFor(userId, reportId).stream()
                .collect(Collectors.toMap(ReportStalenessService.AnchorStatus::anchorId,
                        Function.identity(), (a, b) -> a));
        Map<Long, ReportAnchor> anchors = anchorRepository.findByReportId(reportId).stream()
                .collect(Collectors.toMap(ReportAnchor::id, Function.identity(), (a, b) -> a));
        List<AssumptionView> assumptions = assumptionRepository.findByReportId(reportId).stream()
                .map(a -> new AssumptionView(a.seq(), a.statement(), anchorView(a.anchorId(), anchors, statuses)))
                .toList();
        return new ReportDetailView(report.id(), report.projectId(), report.title(),
                report.status().name(), report.contentMd(), report.limitations(), report.createdAt(),
                clock.instant(), assumptions);
    }

    /**
     * 确认定稿（§3.10）：PENDING_CONFIRM→CONFIRMED，写 journal RESEARCH_EVENT「研报定稿」事件
     * （A21 模式，同事务）。终态再操作 / 并发对手已迁移（CAS 返 0）→ REPORT_INVALID_STATE。
     */
    @Transactional
    public ReportSummaryView confirm(Long userId, Long reportId) {
        ResearchReport report = requireReport(userId, reportId);
        Instant now = clock.instant();
        report.confirm(now).orElseThrow(this::invalidState);
        if (reportRepository.confirmIfPending(reportId, now, now) == 0) {
            throw invalidState(); // 并发对手已迁移（恰一成功，§3.10 CAS）
        }
        ResearchProject project = projectRepository.findById(report.projectId())
                .orElseThrow(() -> new ResearchException(ResearchErrorCode.NOT_FOUND, "研究项目不存在"));
        writeConfirmedEvent(project, report);
        return ReportSummaryView.from(requireReport(userId, reportId));
    }

    /**
     * 拒绝（§3.10）：PENDING_CONFIRM→REJECTED 留痕（reason 必填——域校验先于状态检查，
     * 终态+空白 reason → 400 而非 409）；不写 journal 事件（拒绝可重生成=发新任务）。
     */
    @Transactional
    public ReportSummaryView reject(Long userId, Long reportId, String reason) {
        ResearchReport report = requireReport(userId, reportId);
        Instant now = clock.instant();
        report.reject(reason, now).orElseThrow(this::invalidState);
        if (reportRepository.rejectIfPending(reportId, reason, now) == 0) {
            throw invalidState(); // 并发对手已迁移（恰一成功，§3.10 CAS）
        }
        return ReportSummaryView.from(requireReport(userId, reportId));
    }

    /**
     * 成功回调（Ruling-15/17/7）：任务行已 SUCCEEDED（编排器先行落库，本方法不迁移任务行）。
     * 研报（PENDING_CONFIRM）/锚点/假设落库 + 配额结算，单事务原子。幂等：该任务研报已存在
     * （崩溃重放）则整体 no-op 返回。anchorIndex 对应 anchors 列表下标，越界折未绑定
     * （防御——门通过稿恒在界内）。
     */
    @Override
    @Transactional
    public void onTaskSucceeded(Long taskId, long tokenUsage, SucceededPayload payload) {
        ReportTask task = requireTask(taskId);
        if (reportRepository.findByTaskId(taskId).isPresent()) {
            log.warn("研报任务 {} 成功回调重放：研报已存在，整体 no-op（Ruling-7）", taskId);
            return;
        }
        ResearchReport report = reportRepository.save(ResearchReport.create(taskId,
                task.projectId(), task.userId(), payload.title(), payload.contentMd(),
                payload.limitations(), clock.instant()));
        anchorRepository.saveAll(report.id(), payload.anchors().stream()
                .map(line -> ReportAnchor.of(report.id(), line.snippet(), line.occ(), line.state(),
                        line.toolName(), line.args(), line.asOf(), line.asOfKind(), line.raw()))
                .toList());
        // 落库后回读取锚点 id（saveAll void 口径），id 升序 = 单批落库序 = payload 下标序
        List<Long> anchorIds = anchorRepository.findByReportId(report.id()).stream()
                .map(ReportAnchor::id)
                .toList();
        assumptionRepository.saveAll(report.id(), payload.assumptions().stream()
                .map(line -> new ReportAssumption(null, report.id(), line.seq(), line.statement(),
                        anchorIdOf(line.anchorIndex(), anchorIds)))
                .toList());
        quotaService.settle(task.userId(), tokenUsage);
        log.info("研报任务 {} 产物落库：reportId={}，anchors={}，assumptions={}，settle={} tokens",
                taskId, report.id(), anchorIds.size(), payload.assumptions().size(), tokenUsage);
    }

    /**
     * 失败回调（Ruling-15/17）：任务行已 FAILED（错误诊断已由编排器落行，errorCode/errorMessage
     * 入参仅日志留痕）——仅配额结算（settle 的 @Modifying 依赖本方法事务）。任务行缺失 = 回调
     * 契约违反，NOT_FOUND 快速失败（编排器 failTask 捕获记日志）。重复回调的 settle 累计偏差
     * 偏保守无害（Ruling-7 注记）。
     */
    @Override
    @Transactional
    public void onTaskFailed(Long taskId, long tokenUsage, String errorCode, String errorMessage) {
        ReportTask task = requireTask(taskId);
        quotaService.settle(task.userId(), tokenUsage);
        log.info("研报任务 {} 失败回调结算：settle={} tokens（errorCode={}）", taskId, tokenUsage, errorCode);
    }

    // ———— 内部 ————

    /** 归属双重保障（照 ResearchApplicationService.requireProject 模式）：非本人/不存在一律 404。 */
    private ResearchProject requireProject(Long userId, Long projectId) {
        return projectRepository.findById(projectId)
                .filter(p -> Objects.equals(p.userId(), userId))
                .orElseThrow(() -> new ResearchException(ResearchErrorCode.NOT_FOUND, "研究项目不存在"));
    }

    private ResearchReport requireReport(Long userId, Long reportId) {
        return reportRepository.findById(reportId)
                .filter(r -> Objects.equals(r.userId(), userId))
                .orElseThrow(() -> new ResearchException(ResearchErrorCode.NOT_FOUND, "研报不存在"));
    }

    private ReportTask requireTask(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new ResearchException(ResearchErrorCode.NOT_FOUND, "研报任务不存在"));
    }

    private ResearchException invalidState() {
        return new ResearchException(ResearchErrorCode.REPORT_INVALID_STATE,
                "研报不在待确认状态（已确认/已拒绝或被并发操作）");
    }

    private void writeConfirmedEvent(ResearchProject project, ResearchReport report) {
        journalRepository.save(JournalEntry.create(project.userId(), JournalEntryType.RESEARCH_EVENT,
                project.stockCode(), project.stockName(), null, "研报定稿",
                "研报「" + report.title() + "」定稿",
                null, null, null, null, null, LocalDate.now(clock), clock.instant(), project.id()));
    }

    /** 假设条目锚点视图：未绑定（anchorId 空/锚点行缺失）→ null；state/currentValue 取重查结果。 */
    private static AnchorView anchorView(Long anchorId, Map<Long, ReportAnchor> anchors,
                                         Map<Long, ReportStalenessService.AnchorStatus> statuses) {
        ReportAnchor anchor = anchorId == null ? null : anchors.get(anchorId);
        if (anchor == null) {
            return null;
        }
        ReportStalenessService.AnchorStatus status = statuses.get(anchor.id());
        return new AnchorView(anchor.toolName(), anchor.args(), anchor.asOf(),
                status == null ? null : status.state().name(),
                status == null ? null : status.currentValue());
    }

    /** anchorIndex → 落库锚点 id（payload 下标序 = 落库 id 升序）；越界折 null 未绑定（防御）。 */
    private static Long anchorIdOf(int anchorIndex, List<Long> anchorIds) {
        return anchorIndex >= 0 && anchorIndex < anchorIds.size() ? anchorIds.get(anchorIndex) : null;
    }
}
