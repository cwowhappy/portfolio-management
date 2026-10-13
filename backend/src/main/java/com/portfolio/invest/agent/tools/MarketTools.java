package com.portfolio.invest.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.agent.chart.ChartSpecs;
import com.portfolio.invest.application.market.MarketDataService;
import com.portfolio.invest.domain.market.KlineBar;
import com.portfolio.invest.domain.market.MarketOverview;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolEmitter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 行情域子组件（2026-10-12 自 {@code InvestTools} 按域拆分，纯搬移）：search_stock /
 * get_quote / get_kline / get_news / get_market_overview 五个工具的方法体。
 * 门面保留 @Tool 注解与方法签名（装配面字节不变），此处仅承载实现。
 */
public class MarketTools {

    private static final Logger log = LoggerFactory.getLogger(MarketTools.class);

    private final MarketDataService market;
    private final ObjectMapper mapper;

    public MarketTools(MarketDataService market, ObjectMapper mapper) {
        this.market = market;
        this.mapper = mapper;
    }

    public String searchStock(String query) {
        return ToolResultBlocks.runString(log, mapper, () -> mapper.writeValueAsString(market.search(query)));
    }

    public String getQuote(String code) {
        return ToolResultBlocks.runString(log, mapper, () -> mapper.writeValueAsString(market.quote(code)));
    }

    public ToolResultBlock getKline(String code, String period, Integer limit, ToolEmitter emitter) {
        return ToolResultBlocks.runBlock(log, mapper, () -> {
            List<KlineBar> bars = market.kline(code, period == null ? "day" : period,
                    Math.min(limit == null ? 120 : limit, 500));
            if (bars.isEmpty()) {
                // emit 前守卫：空 bars 不 emit（SSE 不发空 spec），LLM 收安全摘要
                return ToolResultBlock.text(ChartSpecs.klineEmptySummary(code, period));
            }
            // ① SSE 全量（只 emit 一次）：emit 的块永不进 LLM，emit 过则返回值 delta 被 skipSet 跳过
            emitter.emit(ToolResultBlock.builder()
                    .output(TextBlock.builder().text(mapper.writeValueAsString(
                            ChartSpecs.kline(code, period, bars))).build())
                    .build());
            // ② LLM 摘要：返回值只进 Msg/stateStore
            return ToolResultBlock.text(ChartSpecs.klineSummary(code, period, bars));
        });
    }

    public String getNews(String code, Integer limit) {
        return ToolResultBlocks.runString(log, mapper,
                () -> mapper.writeValueAsString(market.news(code, Math.min(limit == null ? 10 : limit, 20))));
    }

    public ToolResultBlock getMarketOverview(ToolEmitter emitter) {
        return ToolResultBlocks.runBlock(log, mapper, () -> {
            MarketOverview overview = market.overview();
            // ① SSE 全量（只 emit 一次）：bar 只画涨跌幅，点位不进图
            emitter.emit(ToolResultBlock.builder()
                    .output(TextBlock.builder().text(mapper.writeValueAsString(
                            ChartSpecs.overviewBar(overview))).build())
                    .build());
            // ② LLM 摘要：指数名与点位
            return ToolResultBlock.text(ChartSpecs.overviewSummary(overview));
        });
    }
}
