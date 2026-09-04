package com.portfolio.invest.application.market;

import com.portfolio.invest.domain.market.Financials;
import com.portfolio.invest.domain.market.KlineBar;
import com.portfolio.invest.domain.market.MarketOverview;
import com.portfolio.invest.domain.market.NewsItem;
import com.portfolio.invest.domain.market.Quote;
import com.portfolio.invest.domain.market.StockHit;
import java.util.List;
import java.util.Map;

/** 行情数据门面：web 与 agent 依赖此接口。缓存由 infrastructure 装饰器实现，限流在客户端内。 */
public interface MarketDataService {
    List<StockHit> search(String query);
    Quote quote(String code);

    /**
     * 批量取现价（NFR：组合/资产配置等场景禁止逐只串行单点行情）。
     * 返回能取到行情的标的映射；单只失败仅跳过该 key（不抛整体失败），由调用方按缺失处理。
     */
    Map<String, Quote> quoteBatch(List<String> codes);

    List<KlineBar> kline(String code, String period, int limit);
    Financials financials(String code);
    List<NewsItem> news(String code, int limit);
    MarketOverview overview();
    long probeQuoteLatencyMs();
}
