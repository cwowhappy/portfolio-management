package com.portfolio.invest.web;

import com.portfolio.invest.agent.report.ReportWorkflowOrchestrator;
import com.portfolio.invest.application.research.ReportApplicationService;
import com.portfolio.invest.application.research.ReportApplicationService.StartTaskResult;
import com.portfolio.invest.application.research.ResearchViews.ReportDetailView;
import com.portfolio.invest.application.research.ResearchViews.ReportSummaryView;
import com.portfolio.invest.application.research.ResearchViews.TaskStatusView;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import com.portfolio.invest.web.dto.ConfirmReportRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 研报任务 REST 端点（MS-32 B13，设计规格 §3.12，沿 {@link ResearchController} 风格）：
 * 发起（202 异步受理，taskId + 当日配额余量）/ 轮询 / 研报列表 / 详情（staleness 注入）/
 * 确认分派。本人隔离沿会话 404 语义（服务层归属校验）。
 *
 * <p><strong>发起接线：</strong>startTask（配额 402 / 归属 404 / 活跃 409 语义均在服务层
 * 抛出）成功后以 taskId 调编排器 {@link ReportWorkflowOrchestrator#submit}（web→agent
 * 合法边，项目信息编排器自查）。执行线程池拒绝（有界 0 队列）由编排器同步落
 * {@code FAILED(AGENT_ERROR, 执行队列已满)}（B9 已核实 fail-closed）——控制器无需补偿。</p>
 *
 * <p><strong>确认分派：</strong>action ∈ CONFIRM|REJECT（{@link ConfirmReportRequest}
 * Bean Validation 400）；REJECT 时 reason 必填走域校验（REJECT_REASON_REQUIRED → 400，
 * 域校验先于状态检查——终态+空白 reason → 400 而非 409，B2 Minor-4 优先序）。</p>
 */
@RestController
@RequestMapping("/api/research")
public class ReportTaskController {

    private final ReportApplicationService service;
    private final ReportWorkflowOrchestrator orchestrator;

    public ReportTaskController(ReportApplicationService service,
                                ReportWorkflowOrchestrator orchestrator) {
        this.service = service;
        this.orchestrator = orchestrator;
    }

    /** 发起研报（F01）：202 异步受理，body {taskId, quotaRemaining}（前端提示条）。 */
    @PostMapping("/projects/{projectId}/report-tasks")
    public ResponseEntity<StartTaskResult> startTask(Authentication auth,
                                                     @PathVariable Long projectId) {
        Long userId = currentUserId(auth);
        StartTaskResult result = service.startTask(userId, projectId);
        orchestrator.submit(result.taskId(), userId, projectId);
        return ResponseEntity.accepted().body(result);
    }

    /** 轮询最新任务：FAILED 透出 errorCode/errorMessage，SUCCEEDED 回连 reportId。 */
    @GetMapping("/projects/{projectId}/report-tasks/latest")
    public TaskStatusView latestTask(Authentication auth, @PathVariable Long projectId) {
        return service.latestTask(currentUserId(auth), projectId);
    }

    /** 项目研报列表（createdAt 倒序，仓库保证）。 */
    @GetMapping("/projects/{projectId}/reports")
    public List<ReportSummaryView> reports(Authentication auth, @PathVariable Long projectId) {
        return service.reports(currentUserId(auth), projectId);
    }

    /** 研报详情（F02）：假设条目携带锚点视图（anchor.state 为打开时重查四态）。 */
    @GetMapping("/reports/{reportId}")
    public ReportDetailView reportDetail(Authentication auth, @PathVariable Long reportId) {
        return service.reportDetail(currentUserId(auth), reportId);
    }

    /** 确认定稿 / 拒绝留痕（§3.10）：body {action, reason?}，终态再操作 → 409。 */
    @PostMapping("/reports/{reportId}/confirm")
    public ReportSummaryView confirmReport(Authentication auth, @PathVariable Long reportId,
                                           @Valid @RequestBody ConfirmReportRequest cmd) {
        Long userId = currentUserId(auth);
        if ("REJECT".equals(cmd.action())) {
            return service.reject(userId, reportId, cmd.reason());
        }
        return service.confirm(userId, reportId);
    }

    private static Long currentUserId(Authentication auth) {
        return ((AuthenticatedUser) auth.getPrincipal()).user().id();
    }
}
