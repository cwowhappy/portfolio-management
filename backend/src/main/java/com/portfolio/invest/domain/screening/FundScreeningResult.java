package com.portfolio.invest.domain.screening;

import java.math.BigDecimal;

/** ETF 筛选结果行（etf_basic 目录读模型）；null 字段=未知，展示层以「—」呈现。 */
public record FundScreeningResult(
        String fundCode, String fundName, BigDecimal feeRate, BigDecimal scale,
        String trackingIndexName, String category, BigDecimal trackingError1y
) {}
