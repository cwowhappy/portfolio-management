package com.portfolio.invest.application.screening;

import java.math.BigDecimal;
import java.time.Instant;

/** 自选行视图：最新快照（收盘口径）+ 实时现价（可空）+ 添加时间。 */
public record WatchlistItemView(
        String stockCode, String stockName, String industryName,
        BigDecimal price, BigDecimal peTtm, BigDecimal pb,
        BigDecimal dividendYield, BigDecimal totalMv, Instant addedAt) {}
