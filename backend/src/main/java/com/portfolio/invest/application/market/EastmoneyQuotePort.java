package com.portfolio.invest.application.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.portfolio.invest.domain.market.Quote;

/**
 * 东财行情帧解析端口。字段码（f43/f57/f58...）→ {@link Quote} 的映射由 infrastructure 侧
 * {@code EastmoneyFormat} + domain {@code MarketDataParser} 完成（2026-10-12 B8 下沉），
 * application 只依赖本端口以保持依赖方向（application 禁止直引 infrastructure）。
 */
@FunctionalInterface
public interface EastmoneyQuotePort {

    Quote parse(JsonNode root);
}
