package com.portfolio.invest.domain.eval;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * eval 运行留痕行（eval_run，V5）：触发时先插 RUNNING 行、收割时 update 全列——本 record
 * 为读取形态（收割侧组装 baseline/上一跑 {@code RunResult} 的数据源）。
 *
 * <p>口径约定（与 V5 DDL 注释对齐）：
 * <ul>
 *   <li>total_fail 为<b>含 ERROR</b>的合并列（收割口径），total_error 单列留痕其中框架
 *       异常数——重建原始计数时 {@code 原始 fail = total_fail - total_error}（回归判定的
 *       分母合并由 judge 统一执行，收割侧严禁把本列直灌判定输入）；</li>
 *   <li>by_category 值为长度 3 的 {@code int[]}：[0]=pass、[1]=fail、[2]=error（分类内
 *       <b>原始</b>计数，与总口径同构，非合并形态）；</li>
 *   <li>prompt_versions 为当跑全资产版本快照（asset_key → version；可比性指纹取其中
 *       {@code rubric.*} 子集与 baseline 全等比对）。</li>
 * </ul>
 *
 * @param id               主键（IDENTITY）
 * @param triggeredBy      触发方式（SCHEDULED/MANUAL——落库真值归调度侧，报告内恒 MANUAL 不采信）
 * @param track            轨道（AGENT=对话轨，判定/baseline 语义；EXTRACT=抽取轨，仅留痕——
 *                         MS-30 跟进②，V6 列，存量行回填 AGENT）
 * @param status           运行状态（RUNNING/COMPLETED/PARTIAL/FAILED）
 * @param startedAt        触发时间
 * @param finishedAt       收割完成时间（RUNNING 行为 null）
 * @param totalPass        总 PASS 题数
 * @param totalFail        总失败数（含 ERROR 合并口径）
 * @param totalError       其中框架异常数
 * @param byCategory       分类 → [pass, fail, error]（原始计数）
 * @param promptVersions   全资产版本快照（asset_key → version）
 * @param questionBankHash 题库聚合 hash（可比性指纹之一；null 视为不可比旧档）
 * @param alertStatus      告警状态（NONE/DEGRADED/RECOVERED）
 * @param baseline         是否当前基准（部分唯一索引恒最多一行）
 * @param baselineCandidate 恢复跑候选（生效仍需人工 PUT）
 * @param verdictReasons   判定理由清单
 * @param durationMs       运行时长（报告 runMeta.totalDurationMs）
 * @param reportPath       报告归档路径（per-run 副本，防后续轮次覆盖同名报告）
 */
public record EvalRunRow(
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

    /** V5 CHECK 值域常量（触发先插 RUNNING，收割落终态三值）。 */
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_FAILED = "FAILED";

    /** 告警状态值域（判定五态的库表落点：INCOMPARABLE/NO_BASELINE 落 NONE）。 */
    public static final String ALERT_NONE = "NONE";
    public static final String ALERT_DEGRADED = "DEGRADED";
    public static final String ALERT_RECOVERED = "RECOVERED";

    /**
     * 轨道值域（MS-30 跟进②，V6 track 列）：AGENT=对话轨（既有回归判定/baseline 语义，
     * 存量行回填值）；EXTRACT=抽取轨（落库留痕，不参与判定/baseline——v1 简化，收割侧
     * alert_status 恒 NONE）。
     */
    public static final String TRACK_AGENT = "AGENT";
    public static final String TRACK_EXTRACT = "EXTRACT";

    public EvalRunRow {
        byCategory = byCategory == null ? Map.of() : Map.copyOf(byCategory);
        promptVersions = promptVersions == null ? Map.of() : Map.copyOf(promptVersions);
        verdictReasons = verdictReasons == null ? List.of() : List.copyOf(verdictReasons);
    }

    /**
     * 基准置位资格（MS-30 B5，设计规格 §2.5/Review Focus #5）：仅 COMPLETED 且非 DEGRADED
     * 跑可置 baseline=true——PARTIAL（数字不完整）/FAILED（无产出）/DEGRADED（劣化跑）均拒。
     * RECOVERED 恢复跑数字完整且判定通过，可置（baseline_candidate 的人工确认通道）。
     * 仅对话轨有资格（MS-30 跟进②）：EXTRACT 轨不参与判定/baseline——若放行，恒一基准
     * 会被抽取行占位而 findBaseline（track='AGENT' 过滤）静默失明。
     */
    public boolean eligibleAsBaseline() {
        return STATUS_COMPLETED.equals(status) && !ALERT_DEGRADED.equals(alertStatus)
                && TRACK_AGENT.equals(track);
    }
}
