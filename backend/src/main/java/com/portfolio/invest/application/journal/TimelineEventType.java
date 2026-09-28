package com.portfolio.invest.application.journal;

/** 时间线事件类型：M08 三类（买/卖/分红）+ journal 五类（含研究事件）。 */
public enum TimelineEventType {
    BUY, SELL, DIVIDEND, BUY_MEMO, SELL_MEMO, RESEARCH_NOTE, REVIEW, RESEARCH_EVENT
}
