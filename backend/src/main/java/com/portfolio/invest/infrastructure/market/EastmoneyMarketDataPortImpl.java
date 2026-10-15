package com.portfolio.invest.infrastructure.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.portfolio.invest.application.market.EastmoneyMarketDataPort;
import com.portfolio.invest.domain.market.FinancialIndicator;
import com.portfolio.invest.domain.market.KlineBar;
import com.portfolio.invest.domain.market.MarketOverview;
import com.portfolio.invest.domain.market.NewsItem;
import com.portfolio.invest.domain.market.Quote;
import com.portfolio.invest.domain.market.StockHit;
import java.util.List;
import org.springframework.stereotype.Component;

/** 东财行情帧解析端口的 Spring 实现：{@link EastmoneyFormat}（extract+parse 组合）逐方法委托。 */
@Component
public class EastmoneyMarketDataPortImpl implements EastmoneyMarketDataPort {

    @Override
    public Quote parseQuote(JsonNode root) {
        return EastmoneyFormat.parseQuote(root);
    }

    @Override
    public List<StockHit> parseSearch(JsonNode root) {
        return EastmoneyFormat.parseSearch(root);
    }

    @Override
    public List<KlineBar> parseKline(JsonNode root) {
        return EastmoneyFormat.parseKline(root);
    }

    @Override
    public List<FinancialIndicator> parseFinancialIndicators(JsonNode root) {
        return EastmoneyFormat.parseFinancials(root);
    }

    @Override
    public List<NewsItem> parseNews(JsonNode root) {
        return EastmoneyFormat.parseNews(root);
    }

    @Override
    public MarketOverview buildOverview(JsonNode root) {
        return EastmoneyFormat.buildOverview(root);
    }
}
