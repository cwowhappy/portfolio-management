package com.portfolio.invest.web;

import com.portfolio.invest.application.research.CreateProjectCommand;
import com.portfolio.invest.application.research.ResearchApplicationService;
import com.portfolio.invest.application.research.ResearchApplicationService.SaveFalsifierItem;
import com.portfolio.invest.application.research.ResearchApplicationService.UpdateProjectCommand;
import com.portfolio.invest.application.research.ResearchViews.FalsifierView;
import com.portfolio.invest.application.research.ResearchViews.ProjectDetailView;
import com.portfolio.invest.application.research.ResearchViews.ProjectView;
import com.portfolio.invest.application.research.ResearchViews.StrategyView;
import com.portfolio.invest.application.research.SaveStrategyCommand;
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

    private static Long currentUserId(Authentication auth) {
        return ((AuthenticatedUser) auth.getPrincipal()).user().id();
    }
}
