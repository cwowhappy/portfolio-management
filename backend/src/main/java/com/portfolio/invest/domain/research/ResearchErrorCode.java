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
}
