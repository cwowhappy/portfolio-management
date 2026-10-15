package com.portfolio.invest.application.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.portfolio.invest.domain.market.FinancialIndicator;
import com.portfolio.invest.domain.market.KlineBar;
import com.portfolio.invest.domain.market.MarketOverview;
import com.portfolio.invest.domain.market.NewsItem;
import com.portfolio.invest.domain.market.Quote;
import com.portfolio.invest.domain.market.StockHit;
import java.util.List;

/**
 * 东财行情帧解析端口（2026-10-15 B2 下沉收口：由第三轮 {@code EastmoneyQuotePort} 扩展
 * 改名而来，注入点仅 OrchestratingMarketDataService 一处，随改名同步）。六类帧的
 * 「字段码 + JSON 路径导航 → Raw* 中间结构 → domain 归一化」组合由 infrastructure 侧
 * {@code EastmoneyFormat} + domain {@code MarketDataParser} 完成，application 只依赖本
 * 端口以保持依赖方向（application 禁止直引 infrastructure）。
 */
public interface EastmoneyMarketDataPort {

    /** 实时行情（fltt=2 帧）→ {@link Quote}。 */
    Quote parseQuote(JsonNode root);

    /** 股票搜索（QuotationCodeTable 帧，非 A 股条目已在基础设施侧过滤）→ 命中列表。 */
    List<StockHit> parseSearch(JsonNode root);

    /** K线（data.klines 帧）→ K线柱列表（手→股归一化在 domain 完成）。 */
    List<KlineBar> parseKline(JsonNode root);

    /** 财务指标序列（F10 数据中台帧）→ 指标列表（报告期截断归一化在 domain 完成）。 */
    List<FinancialIndicator> parseFinancialIndicators(JsonNode root);

    /** 新闻（cmsArticleWebOld 帧）→ 条目列表（HTML 剥离/摘要截断在 domain 完成）。 */
    List<NewsItem> parseNews(JsonNode root);

    /** 指数速览（push2 diff 帧）→ 大盘速览（空数据抛 BAD_RESPONSE 的口径不变）。 */
    MarketOverview buildOverview(JsonNode root);
}
