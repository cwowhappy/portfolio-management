package com.portfolio.invest.domain.industry;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 最新行业估值行（tradingDay 即该行底层 trading_day，MS-29 B4 时点透出）。 */
public record IndustryValuationRow(String industryCode, String industryName, LocalDate tradingDay,
        BigDecimal pe, BigDecimal pb, BigDecimal roe, BigDecimal dividendYield) {}
