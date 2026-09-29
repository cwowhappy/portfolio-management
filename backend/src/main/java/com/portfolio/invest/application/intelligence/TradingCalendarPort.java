package com.portfolio.invest.application.intelligence;

import java.time.LocalDate;

/**
 * 交易日历端口（D5/F04：仅交易日生成与推送盘前简报）。infrastructure/persistence 实现
 * 直读 collector Alembic 建的跨服务契约表 trading_calendar（行存在即交易日）；
 * 表空/表缺失/无记录可裁决时降级「周一~周五」近似并 log.warn——冷启动（collector
 * 首刷前）不阻断。
 */
public interface TradingCalendarPort {

    /** 当日是否 A 股交易日。 */
    boolean isTradingDay(LocalDate date);
}
