package com.portfolio.invest.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.agent.chart.ChartSpecs;
import com.portfolio.invest.domain.market.Financials;
import com.portfolio.invest.domain.screening.ScreeningCriteria;
import com.portfolio.invest.domain.screening.SortDirection;
import com.portfolio.invest.domain.screening.StockScreeningResult;
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
import java.math.BigDecimal;
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
    private final com.portfolio.invest.application.screening.ScreeningApplicationService screening;
    private final com.portfolio.invest.application.market.FinancialQueryService financialQuery;
    private final ObjectMapper mapper;

    public InvestTools(
            MarketDataService market,
            ValuationApplicationService valuationApplicationService,
            com.portfolio.invest.application.screening.ScreeningApplicationService screening,
            com.portfolio.invest.application.market.FinancialQueryService financialQuery,
            ObjectMapper mapper) {
        this.market = market;
        this.valuationApplicationService = valuationApplicationService;
        this.screening = screening;
        this.financialQuery = financialQuery;
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
            if (f.indicators().isEmpty()) {
                // emit 前守卫：空指标不 emit（摘要 get(0) 也会 IOOBE），LLM 收安全摘要
                return ToolResultBlock.text(ChartSpecs.financialsEmptySummary(f));
            }
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
        return run(() -> mapper.writeValueAsString(market.news(code, Math.min(limit == null ? 10 : limit, 20))));
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
            if (history.snapshots().isEmpty()) {
                // emit 前守卫：冷库期（ValuationApplicationService 空表）合法产出空——不 emit 空走势 spec
                return ToolResultBlock.text(ChartSpecs.valuationEmptySummary());
            }
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

    @Tool(
            name = "screen_stocks",
            description = "多条件筛选A股（全部条件 AND 组合，至少给一个）：PE(TTM)/PB/股息率/ROE%/ROA%/毛利率%/资产负债率%/流动比率/营收同比%/净利同比%/总市值下限(元)/换手率%，可叠加申万行业码（如 801140）与指数成分（000300 沪深300 / 000905 中证500）。结果表格 + 摘要。",
            readOnly = true,
            concurrencySafe = true)
    public ToolResultBlock screenStocks(
            @ToolParam(name = "peTtmMax", description = "PE(TTM) 上限，如 20") Double peTtmMax,
            @ToolParam(name = "pbMax", description = "PB 上限") Double pbMax,
            @ToolParam(name = "dividendYieldMin", description = "股息率下限 %，如 3") Double dividendYieldMin,
            @ToolParam(name = "roeMin", description = "ROE 下限 %，如 15") Double roeMin,
            @ToolParam(name = "roaMin", description = "ROA 下限 %") Double roaMin,
            @ToolParam(name = "grossMarginMin", description = "毛利率下限 %") Double grossMarginMin,
            @ToolParam(name = "debtToAssetsMax", description = "资产负债率上限 %，如 60") Double debtToAssetsMax,
            @ToolParam(name = "currentRatioMin", description = "流动比率下限，如 1.5") Double currentRatioMin,
            @ToolParam(name = "revenueYoyMin", description = "营收同比下限 %") Double revenueYoyMin,
            @ToolParam(name = "netprofitYoyMin", description = "净利同比下限 %") Double netprofitYoyMin,
            @ToolParam(name = "totalMvMin", description = "总市值下限（元），如 1e10=百亿") Double totalMvMin,
            @ToolParam(name = "turnoverRateMin", description = "换手率下限 %") Double turnoverRateMin,
            @ToolParam(name = "industryCode", description = "申万一级行业码，可空") String industryCode,
            @ToolParam(name = "indexCode", description = "指数成分范围：000300/000905，可空") String indexCode,
            @ToolParam(name = "sortBy", description = "排序字段，默认 total_mv") String sortBy,
            @ToolParam(name = "sortDirection", description = "asc/desc，默认 desc") String sortDirection,
            @ToolParam(name = "limit", description = "返回条数，默认 20，最大 50") Integer limit,
            ToolEmitter emitter) {
        return runBlock(() -> {
            String sort = sortBy == null || sortBy.isBlank() ? "total_mv" : sortBy;
            if (!ScreeningCriteria.SORTABLE_FIELDS.contains(sort)) {
                return ToolResultBlock.text(toError("不支持的排序字段: " + sort,
                        "可用: " + ScreeningCriteria.SORTABLE_FIELDS));
            }
            ScreeningCriteria criteria = new ScreeningCriteria(
                    bd(peTtmMax), bd(pbMax), bd(dividendYieldMin), bd(roeMin), bd(roaMin),
                    bd(grossMarginMin), bd(debtToAssetsMax), bd(currentRatioMin), bd(revenueYoyMin),
                    bd(netprofitYoyMin), bd(totalMvMin), bd(turnoverRateMin),
                    industryCode == null || industryCode.isBlank() ? null : industryCode,
                    indexCode == null || indexCode.isBlank() ? null : indexCode,
                    sort,
                    "asc".equalsIgnoreCase(sortDirection) ? SortDirection.ASC : SortDirection.DESC,
                    Math.max(1, Math.min(limit == null ? 20 : limit, 50)));
            if (!criteria.hasAnyCondition()) {
                return ToolResultBlock.text(toError("至少需要一个筛选条件", "请给出 PE/ROE/市值等至少一个条件"));
            }
            List<StockScreeningResult> results = screening.screen(criteria);
            if (results.isEmpty()) {
                return ToolResultBlock.text(ChartSpecs.screeningSummary(results)); // 空结果不 emit 空表
            }
            emitter.emit(ToolResultBlock.builder()
                    .output(TextBlock.builder().text(mapper.writeValueAsString(
                            ChartSpecs.screeningTable(results))).build())
                    .build());
            return ToolResultBlock.text(ChartSpecs.screeningSummary(results));
        });
    }

    /** Double → BigDecimal（null 透传）。 */
    private static BigDecimal bd(Double v) {
        return v == null ? null : BigDecimal.valueOf(v);
    }

    @Tool(
            name = "analyze_financials",
            description = "财报解读：杜邦三因子拆解（净利率×总资产周转率×权益乘数）+ 近 12 季趋势表（ROE/ROA/毛利率/资产负债率/营收及同比）。用户问「XX 赚钱能力如何/财报怎么样」时调用；股票名称先用 search_stock 换码。",
            readOnly = true,
            concurrencySafe = true)
    public ToolResultBlock analyzeFinancials(
            @ToolParam(name = "code", description = "6位A股代码，如 600519") String code,
            ToolEmitter emitter) {
        return runBlock(() -> {
            com.portfolio.invest.application.market.FinancialAnalysisView view = financialQuery.analyze(code, 12);
            if (view.records().isEmpty()) {
                // 库表空：降级——live 财务摘要 + 趋势积累提示，不 emit 空表
                String live = view.live() == null ? "" : ChartSpecs.financialsSummary(view.live()) + " ";
                return ToolResultBlock.text(live + "库表趋势数据积累中（新股或未采集），暂无法做杜邦拆解。");
            }
            String name = view.live() == null ? code : view.live().name();
            emitter.emit(ToolResultBlock.builder()
                    .output(TextBlock.builder().text(mapper.writeValueAsString(
                            ChartSpecs.financialTrendTable(code, name, view.records()))).build())
                    .build());
            return ToolResultBlock.text(
                    ChartSpecs.financialTrendSummary(name, view.records().size(), view.duPont()));
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
