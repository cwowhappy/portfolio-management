package com.portfolio.invest.infrastructure.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.portfolio.invest.domain.market.MarketDataSource;
import org.springframework.stereotype.Component;

/** MarketDataSource 端口的 HTTP 实现：委托东财主源 + 新浪/腾讯兜底。 */
@Component
public class MarketHttpDataSource implements MarketDataSource {

    private final EastmoneyClient eastmoney;
    private final SinaClient sina;
    private final TencentClient tencent;

    public MarketHttpDataSource(EastmoneyClient eastmoney, SinaClient sina, TencentClient tencent) {
        this.eastmoney = eastmoney;
        this.sina = sina;
        this.tencent = tencent;
    }

    @Override public JsonNode search(String query) { return eastmoney.search(query); }
    @Override public JsonNode quote(String secid) { return eastmoney.quote(secid); }
    @Override public JsonNode kline(String secid, int klt, int limit) { return eastmoney.kline(secid, klt, limit); }
    @Override public JsonNode financials(String secuCode) { return eastmoney.financials(secuCode); }
    @Override public JsonNode news(String keyword, int limit) { return eastmoney.news(keyword, limit); }
    @Override public JsonNode overview() { return eastmoney.overview(); }
    @Override public String rawQuote(String sinaPrefix, String code) { return sina.rawQuote(sinaPrefix, code); }
    @Override public String rawIndices() { return sina.rawIndices(); }
    @Override public JsonNode fallbackKline(String symbol, String period, int limit) {
        return tencent.kline(symbol, period, limit);
    }
}
