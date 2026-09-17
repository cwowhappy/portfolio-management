package com.portfolio.invest.domain.industry;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A1 取舍（同 StockScreeningResult）：纯数据读模型直接作响应契约。 */
public record IndustryStock(String stockCode, String stockName, BigDecimal totalMv,
        BigDecimal revenue, LocalDate revenueReportDate, BigDecimal roe,
        BigDecimal peTtm, BigDecimal pb, BigDecimal dividendYield, Prosperity prosperity) {}
