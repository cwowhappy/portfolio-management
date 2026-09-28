package com.portfolio.invest.domain.research;

/** research 域错误码（常量类，照 {@code JournalErrorCode} 先例；后续任务按需扩展）。 */
public final class ResearchErrorCode {
    private ResearchErrorCode() {}

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
}
