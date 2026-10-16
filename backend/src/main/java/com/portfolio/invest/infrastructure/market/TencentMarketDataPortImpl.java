package com.portfolio.invest.infrastructure.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.portfolio.invest.application.market.TencentMarketDataPort;
import com.portfolio.invest.domain.market.KlineBar;
import com.portfolio.invest.domain.market.MarketOverview;
import com.portfolio.invest.domain.market.Quote;
import java.util.List;
import org.springframework.stereotype.Component;

/** 腾讯行情帧解析端口的 Spring 实现：{@link TencentFormat} 逐方法委托。 */
@Component
public class TencentMarketDataPortImpl implements TencentMarketDataPort {

    @Override
    public Quote parseQuote(String raw, String code) {
        return TencentFormat.parseQuote(raw, code);
    }

    @Override
    public MarketOverview buildOverview(String raw) {
        return TencentFormat.buildOverview(raw);
    }

    @Override
    public List<KlineBar> parseKline(JsonNode root, String symbol, String period) {
        return TencentFormat.parseKline(root, symbol, period);
    }
}
