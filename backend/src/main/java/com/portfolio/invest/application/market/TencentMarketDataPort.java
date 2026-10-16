package com.portfolio.invest.application.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.portfolio.invest.domain.market.KlineBar;
import com.portfolio.invest.domain.market.MarketOverview;
import com.portfolio.invest.domain.market.Quote;
import java.util.List;

/**
 * 腾讯行情帧解析端口（2026-10-16 审查 L2-二期下沉）：腾讯文本行情/指数行 + K线 JSON 帧 → domain
 * DTO 的整理解析由 infrastructure {@code TencentFormat} 完成（供应商格式知识随供应商走），
 * application 只依赖本端口以保持依赖方向（照 {@link EastmoneyMarketDataPort} 先例）。
 * 异常文案/触发口径与下沉前 domain 解析逐字一致。
 */
public interface TencentMarketDataPort {

    /** 腾讯实时行情（主源）文本帧 → {@link Quote}。 */
    Quote parseQuote(String raw, String code);

    /** 腾讯指数速览文本帧 → {@link MarketOverview}。 */
    MarketOverview buildOverview(String raw);

    /** 腾讯 K线（前复权 JSON 帧）→ K线柱列表（data.{symbol}.{qfq+period} 导航在 infrastructure 侧）。 */
    List<KlineBar> parseKline(JsonNode root, String symbol, String period);
}
