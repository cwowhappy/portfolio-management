package com.portfolio.invest.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.market.MarketDataService;
import com.portfolio.invest.market.MarketDataException;
import com.portfolio.invest.market.dto.FinancialIndicator;
import com.portfolio.invest.market.dto.Financials;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 投研 Agent 的 6 个数据工具：返回 JSON 文本；失败返回结构化错误（不抛异常）。 */
@Component
public class InvestTools {

    private static final Logger log = LoggerFactory.getLogger(InvestTools.class);

    private final MarketDataService market;
    private final ObjectMapper mapper = new ObjectMapper();

    public InvestTools(MarketDataService market) {
        this.market = market;
    }

    @Tool(
            name = "search_stock",
            description = "按股票名称或代码模糊搜索A股，返回候选列表（代码、名称、市场）。用户提到股票名称时先调用本工具获取精确代码。",
            readOnly = true,
            concurrencySafe = true)
    public String searchStock(
            @ToolParam(name = "query", description = "股票名称或代码关键词，如“茅台”或“600519”") String query) {
        return run(() -> mapper.writeValueAsString(market.search(query)));
    }

    @Tool(
            name = "get_quote",
            description = "获取个股实时行情：最新价、涨跌幅、成交量额、高低开、市盈率、市净率等。",
            readOnly = true,
            concurrencySafe = true)
    public String getQuote(
            @ToolParam(name = "code", description = "6位A股代码，如 600519") String code) {
        return run(() -> mapper.writeValueAsString(market.quote(code)));
    }

    @Tool(
            name = "get_kline",
            description = "获取个股历史K线（前复权），用于分析价格趋势、均线与成交量变化。",
            readOnly = true,
            concurrencySafe = true)
    public String getKline(
            @ToolParam(name = "code", description = "6位A股代码，如 600519") String code,
            @ToolParam(name = "period", description = "周期：day 日K / week 周K / month 月K，默认 day") String period,
            @ToolParam(name = "limit", description = "返回根数，默认 60，最大 120") Integer limit) {
        return run(() -> mapper.writeValueAsString(
                market.kline(code, period == null ? "day" : period, limit == null ? 60 : limit)));
    }

    @Tool(
            name = "get_financials",
            description = "获取个股核心财务指标：每股收益、每股净资产、营收、净利润、加权ROE、毛利率，以及当前市盈率/市净率。",
            readOnly = true,
            concurrencySafe = true)
    public String getFinancials(
            @ToolParam(name = "code", description = "6位A股代码，如 600519") String code) {
        return run(() -> {
            Financials f = market.financials(code);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("code", f.code());
            out.put("name", f.name());
            out.put("pe", f.pe());
            out.put("pb", f.pb());
            List<Map<String, Object>> periods = new ArrayList<>();
            for (FinancialIndicator i : f.indicators()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("报告期", i.reportDate());
                row.put("每股收益EPS", i.eps());
                row.put("每股净资产BPS", i.bps());
                row.put("营收(亿元)", round2Yi(i.totalRevenue()));
                row.put("净利润(亿元)", round2Yi(i.netProfit()));
                row.put("加权ROE(%)", i.weightedRoe());
                row.put("毛利率(%)", i.grossMargin());
                periods.add(row);
            }
            out.put("periods", periods);
            return mapper.writeValueAsString(out);
        });
    }

    @Tool(
            name = "get_news",
            description = "获取个股近期新闻（标题、摘要、来源、时间），用于消息面分析。",
            readOnly = true,
            concurrencySafe = true)
    public String getNews(
            @ToolParam(name = "code", description = "6位A股代码，如 600519") String code,
            @ToolParam(name = "limit", description = "返回条数，默认 10，最大 20") Integer limit) {
        return run(() -> mapper.writeValueAsString(market.news(code, limit == null ? 10 : limit)));
    }

    @Tool(
            name = "get_market_overview",
            description = "获取A股大盘速览：上证指数、深证成指、创业板指的最新点位与涨跌幅。",
            readOnly = true,
            concurrencySafe = true)
    public String getMarketOverview() {
        return run(() -> mapper.writeValueAsString(market.overview()));
    }

    private String run(JsonSupplier supplier) {
        try {
            return supplier.get();
        } catch (MarketDataException e) {
            log.warn("工具数据获取失败: code={}, msg={}", e.getCode(), e.getMessage());
            return toError(e.getMessage(), "数据源暂不可用，请稍后重试或换个问法");
        } catch (Exception e) {
            log.error("工具执行异常", e);
            return toError("工具执行失败", "请稍后重试");
        }
    }

    /** 用 ObjectMapper 序列化错误，避免手工拼 JSON 导致非法输出。 */
    private String toError(String message, String hint) {
        try {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("error", message);
            body.put("hint", hint);
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"工具执行失败\",\"hint\":\"请稍后重试\"}";
        }
    }

    private static Double round2Yi(Double v) {
        if (v == null) {
            return null;
        }
        return Math.round(v / 1_0000_0000.0 * 100.0) / 100.0;
    }

    @FunctionalInterface
    private interface JsonSupplier {
        String get() throws Exception;
    }
}
