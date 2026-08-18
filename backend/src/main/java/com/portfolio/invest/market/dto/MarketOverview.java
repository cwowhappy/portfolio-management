package com.portfolio.invest.market.dto;

import java.util.List;

/** 大盘速览。 */
public record MarketOverview(String time, List<IndexQuote> indices) {}
