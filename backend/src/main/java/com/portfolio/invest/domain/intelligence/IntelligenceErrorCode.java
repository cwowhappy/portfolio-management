package com.portfolio.invest.domain.intelligence;

/** intelligence 域错误码（常量类，照 {@code ResearchErrorCode} 先例；P4 后续任务按需扩展）。 */
public final class IntelligenceErrorCode {
    private IntelligenceErrorCode() {}

    /** 目标资源不存在（如 briefDetail 当日无档）——HTTP 映射 404。 */
    public static final String NOT_FOUND = "NOT_FOUND";
    /** 检索过滤器非法（如 stockIntel 空白标的码）——HTTP 映射 422。 */
    public static final String INVALID_FILTER = "INVALID_FILTER";
}
