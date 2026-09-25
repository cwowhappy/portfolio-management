package com.portfolio.invest.domain.market;

import com.fasterxml.jackson.databind.JsonNode;

/** 行情数据源端口：主源（腾讯）+ 兜底（东财/新浪）的统一访问面。实现见 infrastructure.market。 */
public interface MarketDataSource {
    JsonNode search(String query);
    JsonNode quote(String secid);
    JsonNode kline(String secid, int klt, int limit);
    JsonNode financials(String secuCode);
    JsonNode news(String keyword, int limit);
    JsonNode overview();
    String rawQuote(String sinaPrefix, String code);
    String rawIndices();
    JsonNode fallbackKline(String symbol, String period, int limit);
    String tencentQuote(String symbol);
    String tencentIndices();
}
