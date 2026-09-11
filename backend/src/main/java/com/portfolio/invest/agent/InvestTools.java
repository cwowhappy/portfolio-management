package com.portfolio.invest.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.agent.chart.ChartSpecs;
import com.portfolio.invest.domain.market.Financials;
import com.portfolio.invest.domain.market.KlineBar;
import com.portfolio.invest.domain.market.MarketDataException;
import com.portfolio.invest.domain.market.MarketOverview;
import com.portfolio.invest.application.market.MarketDataService;
import com.portfolio.invest.application.valuation.ValuationApplicationService;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolEmitter;
import io.agentscope.core.tool.ToolParam;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 投研 Agent 的 7 个数据工具：返回 JSON 文本；失败返回结构化错误（不抛异常）。 */
@Component
public class InvestTools {

    private static final Logger log = LoggerFactory.getLogger(InvestTools.class);

    private final MarketDataService market;
    private final ValuationApplicationService valuationApplicationService;
    private final ObjectMapper mapper;

    public InvestTools(
            MarketDataService market,
            ValuationApplicationService valuationApplicationService,
            ObjectMapper mapper) {
        this.market = market;
        this.valuationApplicationService = valuationApplicationService;
        // 注入 Spring Boot 已配置的 ObjectMapper（统一序列化行为；日期已在 ChartSpecs 预转为 ISO 字符串）。
        this.mapper = mapper;
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
            description = "获取个股历史K线（前复权，返回K线图表数据），用于分析价格趋势、均线与成交量变化。",
            readOnly = true,
            concurrencySafe = true)
    public ToolResultBlock getKline(
            @ToolParam(name = "code", description = "6位A股代码，如 600519") String code,
            @ToolParam(name = "period", description = "周期：day 日K / week 周K / month 月K，默认 day") String period,
            @ToolParam(name = "limit", description = "返回根数，默认 120，最大 500") Integer limit,
            ToolEmitter emitter) {                       // 无 @ToolParam → 自动注入（05 §3.2）
        return runBlock(() -> {
            List<KlineBar> bars = market.kline(code, period == null ? "day" : period,
                    Math.min(limit == null ? 120 : limit, 500));
            // ① SSE 全量（只 emit 一次）：emit 的块永不进 LLM，emit 过则返回值 delta 被 skipSet 跳过
            emitter.emit(ToolResultBlock.builder()
                    .output(TextBlock.builder().text(mapper.writeValueAsString(
                            ChartSpecs.kline(code, period, bars))).build())
                    .build());
            // ② LLM 摘要：返回值只进 Msg/stateStore
            return ToolResultBlock.text(ChartSpecs.klineSummary(code, period, bars));
        });
    }

    @Tool(
            name = "get_financials",
            description = "获取个股核心财务指标：每股收益、每股净资产、营收、净利润、加权ROE、毛利率，以及当前市盈率/市净率。",
            readOnly = true,
            concurrencySafe = true)
    public ToolResultBlock getFinancials(
            @ToolParam(name = "code", description = "6位A股代码，如 600519") String code,
            ToolEmitter emitter) {
        return runBlock(() -> {
            Financials f = market.financials(code);
            // ① SSE 全量（只 emit 一次）：table spec（P4 前端接 DataTable 渲染）
            emitter.emit(ToolResultBlock.builder()
                    .output(TextBlock.builder().text(mapper.writeValueAsString(
                            ChartSpecs.financialsTable(f))).build())
                    .build());
            // ② LLM 摘要：PE/PB 与最新报告期
            return ToolResultBlock.text(ChartSpecs.financialsSummary(f));
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
    public ToolResultBlock getMarketOverview(ToolEmitter emitter) {
        return runBlock(() -> {
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

    @Tool(
            name = "get_valuation",
            description = "查询市场估值：全A股PE/PB中位数及历史分位、股债利差(ERP)、主要指数估值、市场情绪温度计。用于回答「现在市场贵不贵/估值高不高」类问题。",
            readOnly = true,
            concurrencySafe = true)
    public ToolResultBlock getValuation(ToolEmitter emitter) {
        return runBlock(() -> {
            // ⚠ 图数据来自 history()（overview() 视图无 peHistory/pbHistory 字段——一手核实）
            var history = valuationApplicationService.history();
            var overview = valuationApplicationService.overview();
            // ① SSE 全量（只 emit 一次）：PE/PB 中位数历史 line（含 null 缺口）
            emitter.emit(ToolResultBlock.builder()
                    .output(TextBlock.builder().text(mapper.writeValueAsString(
                            ChartSpecs.valuationLine(history.snapshots()))).build())
                    .build());
            // ② LLM 摘要：overview() 标量（分位/ERP/温度计）
            return ToolResultBlock.text(ChartSpecs.valuationSummary(overview, history.snapshots().size()));
        });
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

    /** 双通道版 run()：失败不 emit，返回错误 JSON 文本（前端 ChartCard 嗅探降级）。 */
    private ToolResultBlock runBlock(BlockSupplier supplier) {
        try {
            return supplier.get();
        } catch (MarketDataException e) {
            log.warn("工具数据获取失败: code={}, msg={}", e.getCode(), e.getMessage());
            return ToolResultBlock.text(toError(e.getMessage(), "数据源暂不可用，请稍后重试或换个问法"));
        } catch (Exception e) {
            log.error("工具执行异常", e);
            return ToolResultBlock.text(toError("工具执行失败", "请稍后重试"));
        }
    }

    @FunctionalInterface
    private interface JsonSupplier {
        String get() throws Exception;
    }

    @FunctionalInterface
    private interface BlockSupplier {
        ToolResultBlock get() throws Exception;
    }
}
