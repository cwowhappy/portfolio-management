package com.portfolio.invest.application.analytics;

import java.math.BigDecimal;
import java.util.Map;

/** 年度收益表一行（F05）：年份 ×（组合 TWR、各基准同年 TWR、超额 = 组合 − 基准），round 4 位。 */
public record AnnualReturnRow(
        int year,
        BigDecimal portfolioTwr,
        Map<String, BigDecimal> benchmarkTwr,
        Map<String, BigDecimal> excess) {}
