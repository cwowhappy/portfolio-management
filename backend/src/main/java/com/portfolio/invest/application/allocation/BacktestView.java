package com.portfolio.invest.application.allocation;

import java.util.List;

/**
 * 配置回测视图（MS-13 M07-F06）：window/rebalance 回显请求值，windowStart/windowEnd 为实际
 * 曲线窗口（数据日期交集截齐后）；数值全 toPlainString 字符串（null=不可算，前端「—」）。
 */
public record BacktestView(String planName, String windowStart, String windowEnd, String window,
                           String rebalance, List<CurvePointView> curve, String annualizedReturn,
                           String mdd, String sharpe, boolean rfFallback) {}
