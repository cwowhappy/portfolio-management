package com.portfolio.invest.domain.market;

import com.fasterxml.jackson.databind.JsonNode;

/** 行情数据源端口：主源（腾讯行情/指数/K线）+ 兜底（东财全量）+ 末级兜底（新浪行情/指数）的统一访问面。实现见 infrastructure.market。 */
public interface MarketDataSource {
    JsonNode search(String query);
    JsonNode quote(String secid);
    JsonNode kline(String secid, int klt, int limit);
    JsonNode financials(String secuCode);
    JsonNode news(String keyword, int limit);
    JsonNode overview();
    String sinaQuote(String sinaPrefix, String code);
    String sinaIndices();
    JsonNode tencentKline(String symbol, String period, int limit);
    String tencentQuote(String symbol);
    String tencentIndices();
}
