package com.portfolio.invest.domain.screening;

import java.math.BigDecimal;

public record StockScreeningResult(
        String stockCode, String stockName, String industryCode, String industryName,
        BigDecimal peTtm, BigDecimal pb, BigDecimal dividendYield,
        BigDecimal roe, BigDecimal roa, BigDecimal grossMargin,
        BigDecimal debtToAssets, BigDecimal currentRatio,
        BigDecimal revenueYoy, BigDecimal netprofitYoy,
        BigDecimal totalMv, BigDecimal turnoverRate,
        /** 底层估值快照日（yyyy-MM-dd，= max(trading_day)，MS-29 B4 时点透出；表/摘要列不新增）。 */
        String tradeDate
) {}
