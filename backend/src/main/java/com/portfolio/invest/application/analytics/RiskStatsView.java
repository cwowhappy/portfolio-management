package com.portfolio.invest.application.analytics;

/** 风险指标卡（MS-13 F07/F08）：数值 toPlainString 字符串，null=前端「—」；recoveryDate null=回撤进行中。 */
public record RiskStatsView(String mdd, String currentDrawdown, String peakDate, String troughDate,
                            String recoveryDate, long drawdownDays, String sharpe,
                            boolean sharpeRfFallback, String calmar, long windowDays) {}
