package com.portfolio.invest.domain.screening;

import java.math.BigDecimal;

/** 股票搜索候选（自选手动添加用）：最新快照日的代码/名称/行业与核心估值指标。 */
public record StockSearchHit(
        String stockCode, String stockName, String industryName,
        BigDecimal peTtm, BigDecimal pb, BigDecimal totalMv
) {}
