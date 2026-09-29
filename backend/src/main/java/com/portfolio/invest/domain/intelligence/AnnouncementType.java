package com.portfolio.invest.domain.intelligence;

/** 公告类型：决策 #24 十类 + OTHER 兜底。 */
public enum AnnouncementType {
    INCREASE_HOLD,
    DECREASE_HOLD,
    BUYBACK,
    PLACEMENT,
    RELATED_TRANSACTION,
    EARNINGS_FORECAST,
    EARNINGS_FLASH,
    PERIODIC_REPORT,
    EQUITY_INCENTIVE,
    DELISTING_RISK,
    OTHER
}
