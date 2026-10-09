package com.portfolio.invest.application.eval;

import java.util.List;

/**
 * 回归判定结论（MS-30 B4，设计规格 §3.1/§3.2）：五态 status + 理由清单。收割侧落
 * eval_run.alert_status / verdict_reasons；DEGRADED 时 reasons 携带降幅/翻转/分类的
 * 数字化理由行，供告警卡直接取数（Task 6 notifier）。
 *
 * @param status  判定状态（五态之一）
 * @param reasons 理由清单（人读中文，含数字；不可变）
 */
public record JudgeVerdict(Status status, List<String> reasons) {

    /** 判定状态五态（§3.1 分支 + §3.2 alert_status 值域）。 */
    public enum Status {
        /** 可比且全部阈值未命中，无告警。 */
        NONE,
        /** 命中任一劣化阈值；或不可比且绝对完成率 <80%。 */
        DEGRADED,
        /** 上一跑 DEGRADED 且本跑全部阈值未命中（恢复跑，baseline_candidate 待人工确认）。 */
        RECOVERED,
        /** 无 baseline（首跑/基准变更后），不判相对回归。 */
        NO_BASELINE,
        /** 可比性指纹（题库 hash / rubric 版本集）与 baseline 不一致且完成率达标，不做相对判定。 */
        INCOMPARABLE
    }

    public JudgeVerdict {
        reasons = List.copyOf(reasons);
    }
}
