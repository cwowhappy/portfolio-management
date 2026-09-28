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
}
