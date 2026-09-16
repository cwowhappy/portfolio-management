package com.portfolio.invest.domain.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.SortedMap;

/** 基准指数收盘价读侧端口（index_close_history，V13 契约）。 */
public interface IndexClosePort {

    SortedMap<LocalDate, BigDecimal> closes(String indexCode, LocalDate from, LocalDate to);
}
