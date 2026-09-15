package com.portfolio.invest.application.analytics;

/** 交易统计卡片（F06）：数值一律 toPlainString 字符串；profitFactor 为 null 时前端显示「—」。 */
public record TradeStatsView(
        int sellCount,
        int winCount,
        String winRate,
        String avgWin,
        String avgLoss,
        String profitFactor /* null → 前端 "—" */,
        String avgHoldingDays,
        String bestPnl,
        String worstPnl) {}
