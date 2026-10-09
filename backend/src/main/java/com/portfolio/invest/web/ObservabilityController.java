package com.portfolio.invest.web;

import com.portfolio.invest.application.observability.ObservabilityApplicationService;
import com.portfolio.invest.web.dto.CostView;
import com.portfolio.invest.web.dto.LatencyView;
import com.portfolio.invest.web.dto.PromptAssetsView;
import com.portfolio.invest.web.dto.TracePageView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 可观测性看板查询接入层（MS-30 B5，设计规格 §7.1）：trace 明细分页筛选、cost 按日按
 * 工具、latency percentile（p50/p95，percentile_cont SQL 直出）、提示词版本链。路径
 * /api/admin/** 由 SecurityConfig 路径级 hasRole('ADMIN') 保护，无需方法级注解。
 * 本控制器依赖均无 Eval_MODE 门控（纯读 JdbcTemplate 端口），eval 子上下文可安全装配。
 */
@RestController
public class ObservabilityController {

    private final ObservabilityApplicationService service;

    public ObservabilityController(ObservabilityApplicationService service) {
        this.service = service;
    }

    /**
     * 工具调用明细（倒序分页）：from/to 为 ISO-8601 时刻（from 含/to 不含），tool 精确
     * 匹配，failed=true/false 仅失败/仅成功（缺省不限）。
     */
    @GetMapping("/api/admin/observability/trace")
    public TracePageView trace(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String tool,
            @RequestParam(required = false) Boolean failed,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(500) int size) {
        return TracePageView.from(service.trace(from, to, tool, failed, page, size), page);
    }

    /** 成本看板：按日 token 消耗 + 按工具调用统计（days 上限对齐观测保留期 90 天）。 */
    @GetMapping("/api/admin/observability/cost")
    public CostView cost(@RequestParam(defaultValue = "7") @Min(1) @Max(90) int days) {
        return CostView.from(service.cost(days));
    }

    /** 时延看板：轮整体/按日 + 按工具的 p50/p95 百分位。 */
    @GetMapping("/api/admin/observability/latency")
    public LatencyView latency(@RequestParam(defaultValue = "7") @Min(1) @Max(90) int days) {
        return LatencyView.from(service.latency(days));
    }

    /** 提示词版本链（分组、组内版本倒序、最新 current=true——补注目标键为版本行 id）。 */
    @GetMapping("/api/admin/prompt-assets")
    public PromptAssetsView promptAssets() {
        return PromptAssetsView.from(service.assetChains());
    }
}
