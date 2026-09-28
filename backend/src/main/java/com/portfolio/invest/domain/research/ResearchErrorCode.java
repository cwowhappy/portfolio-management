package com.portfolio.invest.domain.research;

/** research 域错误码（常量类，照 {@code JournalErrorCode} 先例；后续任务按需扩展）。 */
public final class ResearchErrorCode {
    private ResearchErrorCode() {}

    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String PROJECT_REQUIRED = "PROJECT_REQUIRED";
    public static final String USER_REQUIRED = "USER_REQUIRED";
    public static final String CODE_BLANK = "CODE_BLANK";
    public static final String NAME_BLANK = "NAME_BLANK";
    public static final String TITLE_BLANK = "TITLE_BLANK";
    public static final String STAGE_REQUIRED = "STAGE_REQUIRED";
    public static final String STAGE_NOT_MAPPED = "STAGE_NOT_MAPPED";
    public static final String VALUATION_RANGE_INVALID = "VALUATION_RANGE_INVALID";
    public static final String STRATEGY_FINALIZED = "STRATEGY_FINALIZED";
    public static final String STRATEGY_REQUIRED = "STRATEGY_REQUIRED";
    public static final String PREDICATE_REQUIRED = "PREDICATE_REQUIRED";
    public static final String THRESHOLD_INVALID = "THRESHOLD_INVALID";
    public static final String NOTE_REQUIRED = "NOTE_REQUIRED";
    /** 建仓计划批次占比 Σratio > 1（D5 唯一硬校验，P3 entry-plan 用例消费，HTTP 映射 422）。 */
    public static final String RATIO_SUM_EXCEEDED = "RATIO_SUM_EXCEEDED";
    /** 建仓计划批次列表为空（of 工厂要求至少一批）。 */
    public static final String BATCH_REQUIRED = "BATCH_REQUIRED";
    /** 建仓批次字段非法：价格区间倒挂/负价/缺失、数量 ≤ 0、占比越界 (0,1]。 */
    public static final String BATCH_INVALID = "BATCH_INVALID";
    /** 凯利手动参数越界：胜率 ∉ (0,1)、赔率 ≤ 0（D23 只做算术，参数域仍须合法）。 */
    public static final String KELLY_PARAM_INVALID = "KELLY_PARAM_INVALID";
    /** 纪律检查上下文缺失。 */
    public static final String CHECK_CONTEXT_REQUIRED = "CHECK_CONTEXT_REQUIRED";
    /** 纪律规则指标缺失（RuleInput 构造，NFR-4 转换侧入参）。 */
    public static final String METRIC_REQUIRED = "METRIC_REQUIRED";
    /** 检查类型缺失。 */
    public static final String CHECK_TYPE_REQUIRED = "CHECK_TYPE_REQUIRED";
    /** 检查项快照为空（append-only 留痕至少一条）。 */
    public static final String CHECK_ITEMS_REQUIRED = "CHECK_ITEMS_REQUIRED";
    /** 检查结论缺失。 */
    public static final String CHECK_RESULT_REQUIRED = "CHECK_RESULT_REQUIRED";
    /** OVERRIDDEN 越过必填理由（空白或超 500 字，D5 留痕最低要求）。 */
    public static final String OVERRIDE_REASON_REQUIRED = "OVERRIDE_REASON_REQUIRED";
    /** 证伪评审结论缺失（F15 四值必选其一）。 */
    public static final String CONCLUSION_REQUIRED = "CONCLUSION_REQUIRED";
    /** 证伪评审理由必填（空白或超 1000 字，F15「结论枚举 + 理由」留痕最低要求）。 */
    public static final String REVIEW_REASON_REQUIRED = "REVIEW_REASON_REQUIRED";
    /** 复盘档位缺失（D7 三档必选其一）。 */
    public static final String TIER_REQUIRED = "TIER_REQUIRED";
    /** 复盘区间起止缺失或倒置（periodStart ≤ periodEnd）。 */
    public static final String REVIEW_PERIOD_INVALID = "REVIEW_PERIOD_INVALID";
    /** 复盘快照空白（F14 创建即定格，不允许无快照复盘）。 */
    public static final String SNAPSHOT_REQUIRED = "SNAPSHOT_REQUIRED";
    /** 复盘作答空白（MS-24 弹性字段集，但作答主体不可为空）。 */
    public static final String ANSWERS_REQUIRED = "ANSWERS_REQUIRED";
    /** 回流目标条目缺失（refluxConfirm 须携带 wiki 条目 id）。 */
    public static final String WIKI_ENTRY_REQUIRED = "WIKI_ENTRY_REQUIRED";
    /** 回流叙述缺失（F16 wiki 条目内容 = 复盘叙述，回流前必填）。 */
    public static final String REFLUX_NARRATIVE_REQUIRED = "REFLUX_NARRATIVE_REQUIRED";
    /** 回流 wiki 写入异常（降级：reflux_state 回 PENDING 不阻断复盘，HTTP 映射 502 可重试）。 */
    public static final String REFLUX_WIKI_UNAVAILABLE = "REFLUX_WIKI_UNAVAILABLE";
    /** 模板改进建议内容必填（空白或超 1000 字，F16「只收集」留痕最低要求）。 */
    public static final String FEEDBACK_CONTENT_REQUIRED = "FEEDBACK_CONTENT_REQUIRED";
}
