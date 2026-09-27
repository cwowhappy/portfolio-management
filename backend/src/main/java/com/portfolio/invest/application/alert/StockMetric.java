package com.portfolio.invest.application.alert;

import java.math.BigDecimal;

/** stock_valuation_daily 当日单票快照（close/peTtm/pb 均可空——缺失即跳过对应维度）。 */
public record StockMetric(String stockCode, String stockName, BigDecimal close, BigDecimal peTtm, BigDecimal pb) {}
