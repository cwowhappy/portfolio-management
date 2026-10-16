package com.portfolio.invest.application.market;

import com.portfolio.invest.domain.market.MarketOverview;
import com.portfolio.invest.domain.market.Quote;

/**
 * 新浪行情帧解析端口（2026-10-16 审查 L2-二期下沉）：新浪文本 wire（行情行/指数行）→ domain DTO
 * 的整理解析由 infrastructure {@code SinaFormat} 完成（供应商格式知识随供应商走），application
 * 只依赖本端口以保持依赖方向（application 禁止直引 infrastructure，照 {@link EastmoneyMarketDataPort}
 * 先例）。异常文案/触发口径与下沉前 domain 解析逐字一致。
 */
public interface SinaMarketDataPort {

    /** 新浪实时行情（末级兜底）文本帧 → {@link Quote}。 */
    Quote parseQuote(String raw, String code);

    /** 新浪指数速览文本帧 → {@link MarketOverview}。 */
    MarketOverview buildOverview(String raw);
}
