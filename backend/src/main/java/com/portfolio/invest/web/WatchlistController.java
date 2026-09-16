package com.portfolio.invest.web;

import com.portfolio.invest.application.screening.AddWatchlistCommand;
import com.portfolio.invest.application.screening.WatchlistApplicationService;
import com.portfolio.invest.application.screening.WatchlistItemView;
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
 * 自选观察列表 REST 接口。路径刻意挂在 /api/watchlist 而非 /api/screening/watchlist：
 * /api/screening/** 是公开前缀（PublicEndpointPaths），自选需要登录——独立前缀天然
 * 落在认证范围内，零安全配置改动。
 */
@RestController
@RequestMapping("/api/watchlist")
public class WatchlistController {

    private final WatchlistApplicationService service;

    public WatchlistController(WatchlistApplicationService service) {
        this.service = service;
    }

    @GetMapping
    public List<WatchlistItemView> list(Authentication auth) {
        return service.list(currentUserId(auth));
    }

    @PostMapping
    public ResponseEntity<Void> add(Authentication auth, @Valid @RequestBody AddWatchlistCommand cmd) {
        service.add(currentUserId(auth), cmd.stockCode().trim());
        // 204（而非 201）：幂等添加语义——重复添加亦成功，响应无体
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{stockCode}")
    public ResponseEntity<Void> remove(Authentication auth, @PathVariable String stockCode) {
        service.remove(currentUserId(auth), stockCode);
        return ResponseEntity.noContent().build();
    }

    private static Long currentUserId(Authentication auth) {
        return ((AuthenticatedUser) auth.getPrincipal()).user().id();
    }
}
