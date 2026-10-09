package com.portfolio.invest.domain.eval;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 收割落库补丁（eval_run 收割 update 的全列载荷，值域见 {@link EvalRunRow} 口径约定）：
 * totalFail 为<b>含 ERROR</b>的合并口径（= 报告原始 fail + error），totalError 单列留痕；
 * byCategory 为分类原始计数（judge 输入的同一份形态）。判定先于本补丁组装（Task 5 硬约束
 * ③：先判后写 alert_status/verdict_reasons）。
 *
 * @param status           终态（COMPLETED/PARTIAL/FAILED 之一；RUNNING 行收割后不复存在）
 * @param finishedAt       收割完成时间
 * @param totalPass        总 PASS 题数（报告原始计数）
 * @param totalFail        总失败数（报告原始 fail + error 合并口径）
 * @param totalError       其中框架异常数（报告原始计数）
 * @param byCategory       分类 → [pass, fail, error]（原始计数）
 * @param promptVersions   全资产版本快照（回流 upsert 后登记表每键最新版本）
 * @param questionBankHash 题库聚合 hash（报告 runMeta）
 * @param alertStatus      告警状态（判定结论映射）
 * @param baselineCandidate 恢复跑候选（仅 COMPLETED 的 RECOVERED 置 true）
 * @param verdictReasons   判定理由清单（人读中文，含数字）
 * @param durationMs       运行时长（报告 runMeta.totalDurationMs）
 * @param reportPath       报告归档路径（per-run 副本）
 */
public record EvalRunHarvest(
        String status,
        Instant finishedAt,
        int totalPass,
        int totalFail,
        int totalError,
        Map<String, int[]> byCategory,
        Map<String, Integer> promptVersions,
        String questionBankHash,
        String alertStatus,
        boolean baselineCandidate,
        List<String> verdictReasons,
        Long durationMs,
        String reportPath) {

    public EvalRunHarvest {
        byCategory = byCategory == null ? Map.of() : Map.copyOf(byCategory);
        promptVersions = promptVersions == null ? Map.of() : Map.copyOf(promptVersions);
        verdictReasons = verdictReasons == null ? List.of() : List.copyOf(verdictReasons);
    }
}
