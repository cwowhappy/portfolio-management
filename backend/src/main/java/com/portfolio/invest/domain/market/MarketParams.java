package com.portfolio.invest.domain.market;

/** 行情入参规整纯函数。应用编排与缓存装饰器共用，保证 key 一致。 */
public final class MarketParams {

    public static final int MAX_LIMIT = 500;
    public static final int KLINE_MIN_LIMIT = 5;
    public static final int KLINE_DEFAULT_LIMIT = 120;
    public static final int NEWS_DEFAULT_LIMIT = 10;
    public static final int NEWS_MAX_LIMIT = 20;

    private MarketParams() {}

    public static String normalizeQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new MarketDataException("INVALID_QUERY", "搜索关键词不能为空");
        }
        return query.trim();
    }

    public static int kltOf(String period) {
        return switch (period == null ? "day" : period) {
            case "day" -> 101;
            case "week" -> 102;
            case "month" -> 103;
            default -> throw new MarketDataException("INVALID_PERIOD", "period 仅支持 day/week/month");
        };
    }

    public static int clampLimit(int limit, int min, int def, int max) {
        return Math.max(min, Math.min(limit <= 0 ? def : limit, max));
    }
}
