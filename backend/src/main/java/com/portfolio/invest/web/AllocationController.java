package com.portfolio.invest.web;

import com.portfolio.invest.application.allocation.AllocationApplicationService;
import com.portfolio.invest.application.allocation.CreatePlanCommand;
import com.portfolio.invest.application.allocation.DeviationView;
import com.portfolio.invest.application.allocation.PlanView;
import com.portfolio.invest.application.allocation.TemplateView;
import com.portfolio.invest.application.allocation.UpdatePlanCommand;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/allocation")
public class AllocationController {

    private final AllocationApplicationService service;

    public AllocationController(AllocationApplicationService service) {
        this.service = service;
    }

    @GetMapping("/templates")
    public List<TemplateView> templates() {
        return service.templates();
    }

    @GetMapping("/plans")
    public List<PlanView> plans(Authentication auth) {
        return service.plans(currentUserId(auth));
    }

    @PostMapping("/plans")
    public ResponseEntity<PlanView> createPlan(Authentication auth, @Valid @RequestBody CreatePlanCommand cmd) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createPlan(currentUserId(auth), cmd));
    }

    @PutMapping("/plans/{planId}")
    public PlanView updatePlan(Authentication auth, @PathVariable Long planId,
                               @Valid @RequestBody UpdatePlanCommand cmd) {
        return service.updatePlan(currentUserId(auth), planId, cmd);
    }

    @PostMapping("/plans/{planId}/activate")
    public PlanView activatePlan(Authentication auth, @PathVariable Long planId) {
        return service.activatePlan(currentUserId(auth), planId);
    }

    @DeleteMapping("/plans/{planId}")
    public ResponseEntity<Void> deletePlan(Authentication auth, @PathVariable Long planId) {
        service.deletePlan(currentUserId(auth), planId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/deviation")
    public DeviationView deviation(Authentication auth) {
        return service.deviation(currentUserId(auth));
    }

    private static Long currentUserId(Authentication auth) {
        return ((AuthenticatedUser) auth.getPrincipal()).user().id();
    }
}
