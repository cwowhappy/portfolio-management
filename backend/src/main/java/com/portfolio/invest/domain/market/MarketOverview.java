package com.portfolio.invest.domain.market;

import java.util.List;

/** 大盘速览。 */
public record MarketOverview(String time, List<IndexQuote> indices) {}
