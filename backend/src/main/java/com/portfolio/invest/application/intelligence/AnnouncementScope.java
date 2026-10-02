package com.portfolio.invest.application.intelligence;

/**
 * 公告检索范围（MS-21 Task 8，F09）：all 全库 / subscription 当前用户订阅标的集 /
 * holdings 当前用户建仓持仓项目标的集（{@code IntelligenceSubscriptionHook} union 语义）。
 *
 * <p>宽松解析：null/空白/未知值一律归 {@link #ALL}——无订阅的普通用户缺省不受限
 * （F09 边界：宁全库不误空）。scope 是带安全缺省的三态开关，不像日期/类型错误
 * 会静默歪曲过滤结果，故不做严格校验。
 */
public enum AnnouncementScope {
    ALL, SUBSCRIPTION, HOLDINGS;

    /** 宽松解析：null/空白/未知值归 ALL。 */
    public static AnnouncementScope parse(String value) {
        if (value == null || value.isBlank()) {
            return ALL;
        }
        return switch (value.trim()) {
            case "subscription" -> SUBSCRIPTION;
            case "holdings" -> HOLDINGS;
            default -> ALL;
        };
    }
}
