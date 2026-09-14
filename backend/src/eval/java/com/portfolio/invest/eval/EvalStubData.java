package com.portfolio.invest.eval;

import com.portfolio.invest.domain.market.Financials;
import com.portfolio.invest.domain.market.KlineBar;
import com.portfolio.invest.domain.market.MarketOverview;
import com.portfolio.invest.domain.market.NewsItem;
import com.portfolio.invest.domain.market.Quote;
import com.portfolio.invest.domain.market.StockHit;
import java.util.List;
import java.util.Map;

/**
 * 每题注入桩行情服务的数据集（题库 YAML 的 {@code stubData} 节，直接绑定领域 record）。
 *
 * <p>未配置的维度视为空：对应工具调用走 {@code UPSTREAM_UNAVAILABLE} 错误路径（InvestTools
 * 转 error JSON 给模型），等价于"数据源不可用"的真实降级语义。
 *
 * @param search search_stock 的固定返回（全量返回，不做查询过滤——桩数据即该题查询结果）
 */
public record EvalStubData(
        List<StockHit> search,
        Map<String, Quote> quotes,
        Map<String, List<KlineBar>> klines,
        Map<String, Financials> financials,
        Map<String, List<NewsItem>> news,
        MarketOverview overview) {

    /** 空数据集（YAML 未写 stubData 或写了空节）。 */
    public static EvalStubData empty() {
        return new EvalStubData(List.of(), Map.of(), Map.of(), Map.of(), Map.of(), null);
    }

    public List<StockHit> searchOrNull() {
        return search == null ? List.of() : search;
    }

    public Map<String, Quote> quotesOrNull() {
        return quotes == null ? Map.of() : quotes;
    }

    public Map<String, List<KlineBar>> klinesOrNull() {
        return klines == null ? Map.of() : klines;
    }

    public Map<String, Financials> financialsOrNull() {
        return financials == null ? Map.of() : financials;
    }

    public Map<String, List<NewsItem>> newsOrNull() {
        return news == null ? Map.of() : news;
    }
}
