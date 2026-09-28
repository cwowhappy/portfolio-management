package com.portfolio.invest.web;

import com.portfolio.invest.application.research.CreateProjectCommand;
import com.portfolio.invest.application.research.ResearchApplicationService;
import com.portfolio.invest.application.research.ResearchApplicationService.PreviewCheckCommand;
import com.portfolio.invest.application.research.ResearchApplicationService.SaveEntryPlanCommand;
import com.portfolio.invest.application.research.ResearchApplicationService.SaveFalsifierItem;
import com.portfolio.invest.application.research.ResearchApplicationService.SubmitCheckCommand;
import com.portfolio.invest.application.research.ResearchApplicationService.SubmitFalsifierReviewCommand;
import com.portfolio.invest.application.research.ResearchApplicationService.UpdateProjectCommand;
import com.portfolio.invest.application.research.ResearchViews.CheckRecordView;
import com.portfolio.invest.application.research.ResearchViews.EntryPlanView;
import com.portfolio.invest.application.research.ResearchViews.FalsifierHitView;
import com.portfolio.invest.application.research.ResearchViews.FalsifierReviewView;
import com.portfolio.invest.application.research.ResearchViews.FalsifierView;
import com.portfolio.invest.application.research.ResearchViews.ProjectDetailView;
import com.portfolio.invest.application.research.ResearchViews.ProjectView;
import com.portfolio.invest.application.research.ResearchViews.StrategyView;
import com.portfolio.invest.application.research.SaveStrategyCommand;
import com.portfolio.invest.domain.research.CheckItemResult;
import com.portfolio.invest.domain.research.ProjectStatus;
import com.portfolio.invest.domain.research.ResearchStage;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 研究项目 REST 端点（/api/research/**，登录 + 非本人一律 404）。 */
@RestController
@RequestMapping("/api/research")
public class ResearchController {

    private final ResearchApplicationService service;

    public ResearchController(ResearchApplicationService service) {
        this.service = service;
    }

    @PostMapping("/projects")
    public ResponseEntity<ProjectView> createProject(Authentication auth,
                                                     @Valid @RequestBody CreateProjectCommand cmd) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createProject(currentUserId(auth), cmd));
    }

    @GetMapping("/projects")
    public List<ProjectView> projects(Authentication auth,
                                      @RequestParam(required = false) ResearchStage stage,
                                      @RequestParam(required = false) ProjectStatus status,
                                      @RequestParam(required = false) String q) {
        return service.listProjects(currentUserId(auth), stage, status, q);
    }

    @GetMapping("/projects/{projectId}")
    public ProjectDetailView getProject(Authentication auth, @PathVariable Long projectId) {
        return service.getProject(currentUserId(auth), projectId);
    }

    @PatchMapping("/projects/{projectId}")
    public ProjectDetailView updateProject(Authentication auth, @PathVariable Long projectId,
                                           @Valid @RequestBody UpdateProjectCommand cmd) {
        return service.updateProject(currentUserId(auth), projectId, cmd);
    }

    @PostMapping("/projects/{projectId}/archive")
    public ProjectView archiveProject(Authentication auth, @PathVariable Long projectId) {
        return service.archiveProject(currentUserId(auth), projectId);
    }

    @GetMapping("/projects/{projectId}/strategy")
    public StrategyView getStrategy(Authentication auth, @PathVariable Long projectId) {
        return service.getStrategy(currentUserId(auth), projectId);
    }

    @PutMapping("/projects/{projectId}/strategy")
    public StrategyView saveStrategyDraft(Authentication auth, @PathVariable Long projectId,
                                          @Valid @RequestBody SaveStrategyCommand cmd) {
        return service.saveStrategyDraft(currentUserId(auth), projectId, cmd);
    }

    @PostMapping("/projects/{projectId}/strategy/finalize")
    public StrategyView finalizeStrategy(Authentication auth, @PathVariable Long projectId) {
        return service.finalizeStrategy(currentUserId(auth), projectId);
    }

    @PostMapping("/projects/{projectId}/strategy/revise")
    public StrategyView reviseStrategy(Authentication auth, @PathVariable Long projectId) {
        return service.reviseStrategy(currentUserId(auth), projectId);
    }

    @GetMapping("/projects/{projectId}/falsifiers")
    public List<FalsifierView> getFalsifiers(Authentication auth, @PathVariable Long projectId) {
        return service.getFalsifiers(currentUserId(auth), projectId);
    }

    @PutMapping("/projects/{projectId}/falsifiers")
    public List<FalsifierView> saveFalsifiers(Authentication auth, @PathVariable Long projectId,
                                              @RequestBody @Valid List<SaveFalsifierItem> items) {
        return service.saveFalsifiers(currentUserId(auth), projectId, items);
    }

    // —— P3-T4：建仓计划 / 纪律检查 / 证伪命中 ——

    /** 建仓计划查询：未保存 → 404（照 strategy 先例）。 */
    @GetMapping("/projects/{projectId}/entry-plan")
    public EntryPlanView getEntryPlan(Authentication auth, @PathVariable Long projectId) {
        return service.getEntryPlan(currentUserId(auth), projectId);
    }

    /** 建仓计划整替保存（plan+batches 同事务；Σratio>1 → 422 唯一硬拒绝）。 */
    @PutMapping("/projects/{projectId}/entry-plan")
    public EntryPlanView saveEntryPlan(Authentication auth, @PathVariable Long projectId,
                                       @Valid @RequestBody SaveEntryPlanCommand cmd) {
        return service.saveEntryPlan(currentUserId(auth), projectId, cmd);
    }

    /** 发起纪律检查（纯读不落库）：返回命中项列表（软提醒不阻断，D5）。 */
    @PostMapping("/projects/{projectId}/checks/preview")
    public List<CheckItemResult> previewCheck(Authentication auth, @PathVariable Long projectId,
                                              @Valid @RequestBody PreviewCheckCommand cmd) {
        return service.previewCheck(currentUserId(auth), projectId, cmd);
    }

    /** 提交检查留痕（append-only + journal 事件；OVERRIDDEN 缺理由 → 422）。 */
    @PostMapping("/projects/{projectId}/checks")
    public ResponseEntity<CheckRecordView> submitCheck(Authentication auth, @PathVariable Long projectId,
                                                       @Valid @RequestBody SubmitCheckCommand cmd) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.submitCheck(currentUserId(auth), projectId, cmd));
    }

    /** 证伪命中合并视图：实时求值 + 历史 hit 留痕（D21）。 */
    @GetMapping("/projects/{projectId}/falsifier/hits")
    public List<FalsifierHitView> getHits(Authentication auth, @PathVariable Long projectId) {
        return service.getHits(currentUserId(auth), projectId);
    }

    // —— P4-T2：证伪评审 ——

    /** 评审留痕列表（createdAt 倒序；suggestStrategyRevise 由结论推导）。 */
    @GetMapping("/projects/{projectId}/falsifier/reviews")
    public List<FalsifierReviewView> getFalsifierReviews(Authentication auth, @PathVariable Long projectId) {
        return service.getFalsifierReviews(currentUserId(auth), projectId);
    }

    /** 提交证伪评审（append-only 落库 + hit 回填 + journal 事件；reason 缺失 → 422；hitId 越项目 → 404）。 */
    @PostMapping("/projects/{projectId}/falsifier/reviews")
    public ResponseEntity<FalsifierReviewView> submitFalsifierReview(Authentication auth,
                                                                     @PathVariable Long projectId,
                                                                     @Valid @RequestBody SubmitFalsifierReviewCommand cmd) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.submitFalsifierReview(currentUserId(auth), projectId, cmd));
    }

    private static Long currentUserId(Authentication auth) {
        return ((AuthenticatedUser) auth.getPrincipal()).user().id();
    }
}
