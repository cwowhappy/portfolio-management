package com.portfolio.invest.web;

import com.portfolio.invest.application.eval.EvalAdminApplicationService;
import com.portfolio.invest.web.dto.BaselineUpdateRequest;
import com.portfolio.invest.web.dto.EvalRunView;
import com.portfolio.invest.web.dto.EvalTriggerResponse;
import com.portfolio.invest.web.dto.PromptAssetNoteRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * eval admin 接入层（MS-30 B5，设计规格 §2.5）：手动触发（202+runId，进行中 409）、
 * 运行历史、baseline 标记（422 资格规则）、提示词版本补注。路径 /api/admin/** 由
 * SecurityConfig 路径级 hasRole('ADMIN') 保护（SecurityConfig.java /api/admin/** 规则），
 * 无需方法级注解；异常→HTTP 映射归 GlobalExceptionHandler。
 *
 * <p>类级 {@code @ConditionalOnExpression} 与 EvalAdminApplicationService/EvalScheduler
 * 同表达式：本控制器强依赖门控缺席的调度链，eval 子进程上下文（--Eval_MODE=true）必须缺席
 * （Task 6 审查 C1 同因防线，守护见 EvalModeContextGuardIntegrationTest）。
 */
@RestController
@ConditionalOnExpression("!'true'.equals('${Eval_MODE:}')")
public class EvalTriggerController {

    private final EvalAdminApplicationService service;

    public EvalTriggerController(EvalAdminApplicationService service) {
        this.service = service;
    }

    /**
     * 手动触发评测：202 受理即返回（子进程非阻塞起跑，状态经 GET /runs 轮询——进行中=
     * 最新行 RUNNING）；进行中抛 EvalRunInProgressException → 409 EVAL_RUN_IN_PROGRESS。
     */
    @PostMapping("/api/admin/eval/run")
    public ResponseEntity<EvalTriggerResponse> run() {
        long runId = service.triggerManual();
        return ResponseEntity.accepted().body(new EvalTriggerResponse(runId));
    }

    /** 运行历史倒序（含得分/判定/baseline/completeness；RUNNING 行原样返回供前端轮询）。 */
    @GetMapping("/api/admin/eval/runs")
    public List<EvalRunView> runs(
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int limit) {
        return service.recentRuns(limit).stream().map(EvalRunView::from).toList();
    }

    /**
     * baseline 置位（Review Focus #5）：body {baseline: true|false}；置 true 仅限
     * COMPLETED 且 alert_status≠DEGRADED 跑（否则 422 ERR_BASELINE_INELIGIBLE），先清
     * 旧行再置本行（恒一基准部分唯一索引）；置 false 恒可（幂等清除）。
     */
    @PutMapping("/api/admin/eval/runs/{id}/baseline")
    public ResponseEntity<Void> setBaseline(@PathVariable Long id,
                                            @Valid @RequestBody BaselineUpdateRequest body) {
        service.setBaseline(id, body.baseline());
        return ResponseEntity.noContent().build();
    }

    /** 版本变更说明补注（需求决策 #12）：{id} 为版本行主键（非资产 key）。 */
    @PutMapping("/api/admin/prompt-assets/{id}/note")
    public ResponseEntity<Void> updateNote(@PathVariable Long id,
                                           @Valid @RequestBody PromptAssetNoteRequest body) {
        service.updateAssetNote(id, body.note());
        return ResponseEntity.noContent().build();
    }
}
