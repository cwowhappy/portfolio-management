package com.portfolio.invest.application.eval;

import com.portfolio.invest.domain.eval.EvalErrorCode;
import com.portfolio.invest.domain.eval.EvalException;
import com.portfolio.invest.domain.eval.EvalRunRepository;
import com.portfolio.invest.domain.eval.EvalRunRow;
import com.portfolio.invest.domain.eval.PromptAssetVersionRepository;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * eval admin 用例（MS-30 B5，设计规格 §2.5）：手动触发（统一走 {@link EvalScheduler}——
 * 互斥/白名单/看护全在调度器）、运行历史、baseline 置位（资格规则 + 恒一索引下先清后置，
 * 事务内）、提示词版本补注。
 *
 * <p>类级 {@code @ConditionalOnExpression} 与 EvalScheduler 同表达式（Task 6 审查 C1 同因
 * 防线）：本服务强依赖门控缺席的调度器，eval 子进程上下文（--Eval_MODE=true）必须整链缺席
 * （web 消费面 {@code EvalTriggerController} 同表达式）。
 */
@Service
@ConditionalOnExpression("!'true'.equals('${Eval_MODE:}')")
public class EvalAdminApplicationService {

    private final EvalScheduler scheduler;
    private final EvalRunRepository runRepository;
    private final PromptAssetVersionRepository assetVersions;

    @Autowired
    public EvalAdminApplicationService(EvalScheduler scheduler, EvalRunRepository runRepository,
                                       PromptAssetVersionRepository assetVersions) {
        this.scheduler = scheduler;
        this.runRepository = runRepository;
        this.assetVersions = assetVersions;
    }

    /**
     * 手动触发评测（triggered_by=MANUAL）：立即返回 runId（ProcessBuilder.start() 非阻塞）；
     * 进行中抛 {@link EvalRunInProgressException}（web 层映射 409）；未启用/缺 key/派生与启动
     * 失败（调度器 {@code IllegalStateException}，文案面向运维）包 {@link
     * EvalTriggerUnavailableException} 透出原文案（web 层映射 503，审查 I2）。
     */
    public long triggerManual() {
        try {
            return scheduler.triggerNow("MANUAL");
        } catch (IllegalStateException e) {
            throw new EvalTriggerUnavailableException(e.getMessage(), e);
        }
    }

    /** 运行历史倒序（RUNNING 行原样返回——前端轮询据此显示进行中；停机残留清扫归部署文档）。 */
    public List<EvalRunRow> recentRuns(int limit) {
        return runRepository.findRecent(limit);
    }

    /**
     * baseline 置位（Review Focus #5）：仅 COMPLETED 且 alert_status≠DEGRADED 跑可置 true
     * （{@link EvalRunRow#eligibleAsBaseline()}），否则抛 ERR_BASELINE_INELIGIBLE（422）。
     * 置 true 时<b>先清旧行再置本行</b>——恒一基准部分唯一索引（idx_eval_run_single_baseline）
     * 的前提，两 UPDATE 须同事务；置 false 仅清本行（幂等，不合格跑也可清）。
     */
    @Transactional
    public void setBaseline(long runId, boolean baseline) {
        EvalRunRow row = runRepository.findById(runId).orElseThrow(
                () -> new EvalException(EvalErrorCode.EVAL_RUN_NOT_FOUND, "eval 运行不存在: " + runId));
        if (baseline && !row.eligibleAsBaseline()) {
            throw new EvalException(EvalErrorCode.ERR_BASELINE_INELIGIBLE,
                    "仅 COMPLETED 且非 DEGRADED 跑可置为基准（当前 status=%s, alert_status=%s）"
                            .formatted(row.status(), row.alertStatus()));
        }
        if (baseline) {
            runRepository.clearBaseline();
        }
        runRepository.updateBaseline(runId, baseline);
    }

    /**
     * 补注版本变更说明（需求决策 #12）：目标是版本行 id（prompt_asset_version 主键，非资产
     * key）；行不存在抛 PROMPT_ASSET_NOT_FOUND。
     */
    public void updateAssetNote(long versionRowId, String note) {
        int updated = assetVersions.updateNote(versionRowId, note);
        if (updated == 0) {
            throw new EvalException(EvalErrorCode.PROMPT_ASSET_NOT_FOUND,
                    "提示词资产版本不存在: " + versionRowId);
        }
    }
}
