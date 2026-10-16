package com.portfolio.invest.infrastructure.market;

import com.portfolio.invest.application.market.SinaMarketDataPort;
import com.portfolio.invest.domain.market.MarketOverview;
import com.portfolio.invest.domain.market.Quote;
import org.springframework.stereotype.Component;

/** 新浪行情帧解析端口的 Spring 实现：{@link SinaFormat} 逐方法委托。 */
@Component
public class SinaMarketDataPortImpl implements SinaMarketDataPort {

    @Override
    public Quote parseQuote(String raw, String code) {
        return SinaFormat.parseQuote(raw, code);
    }

    @Override
    public MarketOverview buildOverview(String raw) {
        return SinaFormat.buildOverview(raw);
    }
}
