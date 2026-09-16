package com.portfolio.invest.web;

import com.portfolio.invest.application.analytics.AnalyticsApplicationService;
import com.portfolio.invest.application.analytics.AnnualReturnRow;
import com.portfolio.invest.application.analytics.NavSeriesView;
import com.portfolio.invest.application.analytics.OverviewView;
import com.portfolio.invest.application.analytics.TradeStatsView;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 收益分析四端点（MS-07）：overview/nav/trade-stats 无数据返回 204 空体
 * （沿 allocation latestAssessment 先例），annual 恒返回数组（无流水为空数组）。
 */
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final AnalyticsApplicationService service;

    public AnalyticsController(AnalyticsApplicationService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public ResponseEntity<OverviewView> overview(Authentication auth) {
        return service.overview(currentUserId(auth))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/nav")
    public ResponseEntity<NavSeriesView> nav(Authentication auth) {
        return service.nav(currentUserId(auth))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/annual")
    public List<AnnualReturnRow> annual(Authentication auth) {
        return service.annual(currentUserId(auth));
    }

    @GetMapping("/trade-stats")
    public ResponseEntity<TradeStatsView> tradeStats(Authentication auth) {
        return service.tradeStats(currentUserId(auth))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    private static Long currentUserId(Authentication auth) {
        return ((AuthenticatedUser) auth.getPrincipal()).user().id();
    }
}
