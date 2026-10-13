package com.portfolio.invest.infrastructure.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.portfolio.invest.application.market.EastmoneyQuotePort;
import com.portfolio.invest.domain.market.Quote;
import org.springframework.stereotype.Component;

/** 东财行情帧解析端口的 Spring 实现：字段码映射 + domain 归一化的组合。 */
@Component
public class EastmoneyQuotePortImpl implements EastmoneyQuotePort {

    @Override
    public Quote parse(JsonNode root) {
        return EastmoneyFormat.parseQuote(root);
    }
}
