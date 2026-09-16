package com.portfolio.invest.domain.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.SortedMap;

/** 个股收盘价读侧端口（stock_valuation_daily.close，V13 契约）。 */
public interface StockClosePort {

    SortedMap<LocalDate, BigDecimal> closes(String stockCode, LocalDate from, LocalDate to);
}
