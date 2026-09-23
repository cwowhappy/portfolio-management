package com.portfolio.invest.domain.market;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 个股财务指标季记录（stock_financial 表读侧值对象；百分数原值口径，见采集端 field_mapping）。 */
public record FinancialRecord(
        LocalDate reportDate, Double roe, Double roa, Double grossMargin,
        Double debtToAssets, Double currentRatio, Double revenueYoy,
        Double netprofitYoy, BigDecimal revenue) {}
