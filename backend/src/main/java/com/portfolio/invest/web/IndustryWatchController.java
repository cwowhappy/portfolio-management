package com.portfolio.invest.web;

import com.portfolio.invest.application.industry.AddIndustryWatchCommand;
import com.portfolio.invest.application.industry.IndustryWatchApplicationService;
import com.portfolio.invest.application.industry.IndustryWatchView;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 行业关注 REST 接口。路径刻意挂在 /api/industry-watch 而非 /api/industry/watch：
 * /api/industry/** 是公开前缀（PublicEndpointPaths），关注需要登录——独立前缀天然
 * 落在认证范围内，零安全配置改动。
 */
@RestController
@RequestMapping("/api/industry-watch")
public class IndustryWatchController {

    private final IndustryWatchApplicationService service;

    public IndustryWatchController(IndustryWatchApplicationService service) {
        this.service = service;
    }

    @GetMapping
    public List<IndustryWatchView> list(Authentication auth) {
        return service.listWatched(currentUserId(auth));
    }

    @PostMapping
    public ResponseEntity<Void> watch(Authentication auth, @Valid @RequestBody AddIndustryWatchCommand cmd) {
        service.watch(currentUserId(auth), cmd.industryCode());
        // 204（而非 201）：幂等关注语义——重复关注亦成功，响应无体
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{industryCode}")
    public ResponseEntity<Void> unwatch(Authentication auth, @PathVariable String industryCode) {
        service.unwatch(currentUserId(auth), industryCode);
        return ResponseEntity.noContent().build();
    }

    private static Long currentUserId(Authentication auth) {
        return ((AuthenticatedUser) auth.getPrincipal()).user().id();
    }
}
