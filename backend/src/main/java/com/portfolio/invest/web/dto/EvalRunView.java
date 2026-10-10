package com.portfolio.invest.web.dto;

import com.portfolio.invest.domain.eval.EvalRunRow;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * eval 运行历史行视图（GET /api/admin/eval/runs，MS-30 B5）：得分/判定/baseline/
 * completeness（status 即收割侧 completeness 终态口径）+ 轨道（AGENT/EXTRACT，MS-30
 * 跟进②——同一晚两轨各一行）全量透出供前端历史表渲染。
 */
public record EvalRunView(
        long id,
        String triggeredBy,
        String track,
        String status,
        Instant startedAt,
        Instant finishedAt,
        int totalPass,
        int totalFail,
        int totalError,
        Map<String, int[]> byCategory,
        Map<String, Integer> promptVersions,
        String questionBankHash,
        String alertStatus,
        boolean baseline,
        boolean baselineCandidate,
        List<String> verdictReasons,
        Long durationMs,
        String reportPath) {

    /** 领域行 → 视图（字段一一对应，无口径换算）。 */
    public static EvalRunView from(EvalRunRow row) {
        return new EvalRunView(row.id(), row.triggeredBy(), row.track(), row.status(), row.startedAt(),
                row.finishedAt(), row.totalPass(), row.totalFail(), row.totalError(),
                row.byCategory(), row.promptVersions(), row.questionBankHash(), row.alertStatus(),
                row.baseline(), row.baselineCandidate(), row.verdictReasons(), row.durationMs(),
                row.reportPath());
    }
}
