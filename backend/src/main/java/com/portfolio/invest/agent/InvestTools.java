package com.portfolio.invest.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.agent.chart.ChartSpecs;
import com.portfolio.invest.agent.research.ResearchDraftSpec;
import com.portfolio.invest.domain.intelligence.MacroPoint;
import com.portfolio.invest.domain.market.Financials;
import com.portfolio.invest.domain.screening.ScreeningCriteria;
import com.portfolio.invest.domain.screening.SortDirection;
import com.portfolio.invest.domain.screening.StockScreeningResult;
import com.portfolio.invest.domain.market.KlineBar;
import com.portfolio.invest.domain.market.MarketDataException;
import com.portfolio.invest.domain.market.MarketOverview;
import com.portfolio.invest.application.intelligence.IntelligenceQueryService;
import com.portfolio.invest.application.intelligence.MacroBriefFilter;
import com.portfolio.invest.application.intelligence.NewsSearchFilter;
import com.portfolio.invest.application.market.MarketDataService;
import com.portfolio.invest.application.valuation.ValuationApplicationService;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolEmitter;
import io.agentscope.core.tool.ToolParam;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 投研 Agent 的数据工具：返回 JSON 文本；失败返回结构化错误（不抛异常）。 */
@Component
public class InvestTools {

    private static final Logger log = LoggerFactory.getLogger(InvestTools.class);

    /** search_news 空结果信封话术：新闻仅 90 天滚动保留（与清理口径一致）。 */
    private static final String NEWS_EMPTY_MESSAGE = "该条件下暂无情报（新闻仅保留 90 天内）";

    /** macro_brief 空政策话术：窗口内无命中（政策库长期保留，非「无数据」）。 */
    private static final String POLICY_EMPTY_NOTE = "该窗口内暂无政策事件（可调大 policyDays 或稍后再试）";

    /** TY 两点无历史序列 note（T4 裁定随行：跨表只合成最新点——note 交代而非空数组，勿当数据缺失）。 */
    private static final String TY_NO_HISTORY_NOTE = "国债收益率仅最新点、无历史序列";

    private final MarketDataService market;
    private final ValuationApplicationService valuationApplicationService;
    private final com.portfolio.invest.application.screening.ScreeningApplicationService screening;
    private final com.portfolio.invest.application.market.FinancialQueryService financialQuery;
    private final com.portfolio.invest.application.industry.IndustryApplicationService industry;
    private final IntelligenceQueryService intelligenceQuery;
    private final ObjectMapper mapper;

    public InvestTools(
            MarketDataService market,
            ValuationApplicationService valuationApplicationService,
            com.portfolio.invest.application.screening.ScreeningApplicationService screening,
            com.portfolio.invest.application.market.FinancialQueryService financialQuery,
            com.portfolio.invest.application.industry.IndustryApplicationService industry,
            IntelligenceQueryService intelligenceQuery,
            ObjectMapper mapper) {
        this.market = market;
        this.valuationApplicationService = valuationApplicationService;
        this.screening = screening;
        this.financialQuery = financialQuery;
        this.industry = industry;
        this.intelligenceQuery = intelligenceQuery;
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
            name = "search_news",
            description = "检索已结构化的财经新闻情报（近 90 天）：按关键词/标的/行业/日期区间/重要度组合过滤，"
                    + "条目含 AI 摘要、方向（利好/利空/中性）与重要度。用户问某标的/行业/主题的新闻、"
                    + "重大事件、利好利空时调用；与 get_news（源站原始新闻流）互补，本工具是抽取后情报。",
            readOnly = true,
            concurrencySafe = true)
    public String searchNews(
            @ToolParam(name = "q", description = "关键词，按标题近似匹配，如“回购”，可空") String q,
            @ToolParam(name = "stock", description = "标的代码，如 600519，可空") String stock,
            @ToolParam(name = "industry", description = "申万一级行业码，如 801140，可空") String industry,
            @ToolParam(name = "from", description = "起始日期 yyyy-MM-dd（含），可空") String from,
            @ToolParam(name = "to", description = "结束日期 yyyy-MM-dd（含），可空") String to,
            @ToolParam(name = "minImportance", description = "重要度下限 0..100，可空") Integer minImportance,
            @ToolParam(name = "limit", description = "返回条数，默认 10，最大 20") Integer limit) {
        // 日期前置校验：格式错走参数错误（run() 兜底会误导为“工具执行失败”）
        LocalDate fromDate;
        LocalDate toDate;
        try {
            fromDate = parseDate(from);
            toDate = parseDate(to);
        } catch (DateTimeParseException e) {
            return ToolResultBlocks.toError(mapper,
                    "日期格式须为 yyyy-MM-dd（如 2026-09-01），实际收到 from=" + from + " to=" + to,
                    "请修正 from/to 后重试");
        }
        return run(() -> {
            IntelligenceQueryService.NewsSearchResult result = intelligenceQuery.searchNews(
                    new NewsSearchFilter(q, stock, industry, fromDate, toDate, minImportance, limit));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("items", result.items());
            if (result.items().isEmpty()) {
                body.put("message", NEWS_EMPTY_MESSAGE);
            } else {
                body.put("total", result.total());
            }
            return mapper.writeValueAsString(body);
        });
    }

    /** 空白串归一 null；非法格式抛 DateTimeParseException 由调用方前置校验兜底。 */
    private static LocalDate parseDate(String s) {
        return s == null || s.isBlank() ? null : LocalDate.parse(s);
    }

    @Tool(
            name = "macro_brief",
            description = "宏观简报：五先行指标（CPI/PPI/PMI/LPR/AFMI）最新值与近 5 期走势、"
                    + "国债收益率（TY1Y/TY10Y 最新点）与近 policyDays 天政策事件（取向/力度/影响领域/摘要/原文链接）。"
                    + "用户问宏观环境、通胀、利率、货币政策或近期政策动态时调用；"
                    + "结果为结构化事实（每指标带数据截止期别 period，引用时注明），"
                    + "「市场含义」由你自己解读，但事实必须来自工具结果。",
            readOnly = true,
            concurrencySafe = true)
    public String macroBrief(
            @ToolParam(name = "indicators", description = "指标码逗号分隔：CPI/PPI/PMI/LPR/AFMI"
                    + "（五先行月度指标）+ TY1Y/TY10Y（国债收益率），可空（缺省全部七项）") String indicators,
            @ToolParam(name = "policyDays", description = "政策事件回看天数，默认 30，最大 90") Integer policyDays) {
        // YAGNI 裁定（MS-22 Task 6）：「市场含义」不在本工具内调 LLM 生成模板句——工具输出
        // 纯结构化事实 + 数据截止期别，市场含义解读留 Agent 对话层基于工具结果自然生成
        // （事实与观点分离；待简报/推送到宏观节的落地需求出现再评估，v1 不做）。
        return run(() -> {
            IntelligenceQueryService.MacroBriefResult result = intelligenceQuery.macroBrief(
                    new MacroBriefFilter(indicators, policyDays));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("indicators", result.indicators().stream()
                    .map(InvestTools::indicatorEntry).toList());
            body.put("policies", result.policies());
            if (result.policies().isEmpty()) {
                body.put("note", POLICY_EMPTY_NOTE);
            } else {
                body.put("total", result.total());
            }
            body.put("missing", result.missing().stream()
                    .map(InvestTools::missingEntry).toList());
            body.put("generatedAt", result.generatedAt());
            return mapper.writeValueAsString(body);
        });
    }

    /** 指标条目 JSON：TY 两点 series 空翻译为 note 字段（T4 裁定随行，勿当数据缺失）。 */
    private static Map<String, Object> indicatorEntry(IntelligenceQueryService.MacroIndicatorView v) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("indicator", v.indicator());
        entry.put("value", v.value());
        if (v.yoy() != null) {
            entry.put("yoy", v.yoy());
        }
        entry.put("period", v.period());
        entry.put("periodType", v.periodType());
        if (v.series().isEmpty() && (MacroPoint.INDICATOR_TY1Y.equals(v.indicator())
                || MacroPoint.INDICATOR_TY10Y.equals(v.indicator()))) {
            entry.put("note", TY_NO_HISTORY_NOTE);
        } else {
            entry.put("series", v.series());
        }
        return entry;
    }

    /** 缺失指标条目 JSON：{indicator,missing:true} 显式列出（F13 缺失不编造不省略）。 */
    private static Map<String, Object> missingEntry(String indicator) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("indicator", indicator);
        entry.put("missing", true);
        return entry;
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
                return ToolResultBlock.text(ToolResultBlocks.toError(mapper, "不支持的排序字段: " + sort,
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
                return ToolResultBlock.text(ToolResultBlocks.toError(mapper, "至少需要一个筛选条件",
                        "请给出 PE/ROE/市值等至少一个条件"));
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
                // 库表空：降级——live 财务摘要 + 趋势积累提示，不 emit 空表。
                // live 指标为空（东财返回 data:[] 的新股）时 financialsSummary 会 get(0) IOOBE，须防护
                String live = (view.live() == null || view.live().indicators().isEmpty())
                        ? "" : ChartSpecs.financialsSummary(view.live()) + " ";
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

    @Tool(
            name = "analyze_industry",
            description = "行业分析：不传行业码返回全行业估值板面（PE/PB 及历史分位排名）；传申万一级码（如 801780 银行）返回该行业估值位 + 头部企业表。用户问「XX 行业怎么样/哪些行业便宜」时调用；行业名先经无参板面对齐行业码再下钻。",
            readOnly = true,
            concurrencySafe = true)
    public ToolResultBlock analyzeIndustry(
            @ToolParam(name = "industryCode", description = "申万一级行业码，可空（空=返回全行业板面）") String industryCode,
            ToolEmitter emitter) {
        return runBlock(() -> {
            List<com.portfolio.invest.application.industry.IndustryBoardView> board = industry.board();
            if (board.isEmpty()) {
                return ToolResultBlock.text(ChartSpecs.industryBoardSummary(board)); // 冷库安全摘要
            }
            if (industryCode == null || industryCode.isBlank()) {
                emitter.emit(ToolResultBlock.builder()
                        .output(TextBlock.builder().text(mapper.writeValueAsString(
                                ChartSpecs.industryBoardTable(board))).build())
                        .build());
                return ToolResultBlock.text(ChartSpecs.industryBoardSummary(board));
            }
            var row = board.stream()
                    .filter(b -> industryCode.equals(b.industryCode())).findFirst().orElse(null);
            if (row == null) {
                // 板面无该行即码未对齐——先返回提示，不调 stocks（service 对不存在码抛 INDUSTRY_NOT_FOUND，
                // 会落进通用错误兜底丢失友好提示）
                return ToolResultBlock.text(
                        "行业码 %s 无板面/成分股数据（请先经板面对齐行业码）。".formatted(industryCode));
            }
            List<com.portfolio.invest.domain.industry.IndustryStock> stocks =
                    industry.stocks(industryCode, "total_mv", "desc", 15);
            if (stocks.isEmpty()) {
                return ToolResultBlock.text(ChartSpecs.industryStocksSummary(row, stocks));
            }
            emitter.emit(ToolResultBlock.builder()
                    .output(TextBlock.builder().text(mapper.writeValueAsString(
                            ChartSpecs.industryStocksTable(row.industryName(), stocks))).build())
                    .build());
            return ToolResultBlock.text(ChartSpecs.industryStocksSummary(row, stocks));
        });
    }

    @Tool(
            name = "research_draft",
            description = "SOP 投研草稿回显（只读）：按阶段提交草稿字段 JSON，返回结构化摘要 + ```research-draft 围栏块"
                    + "（前端按围栏标记提取渲染草稿卡片，与图表 ToolResultBlock 通道不同，草稿走纯文本围栏通道）。"
                    + "stage ∈ NEW_ANALYSIS（新分析）/STRATEGY（策略）/POSITION（建仓计划）/REVIEW（复盘）；"
                    + "draftJson 字段缺失允许（按 null 容忍），类型不符或 stage 非法返回参数错误文本。",
            readOnly = true,
            concurrencySafe = true)
    public String researchDraft(
            @ToolParam(name = "stage", description = "草稿阶段：NEW_ANALYSIS / STRATEGY / POSITION / REVIEW") String stage,
            @ToolParam(name = "draftJson", description = "该阶段草稿字段的 JSON 对象，字段名与阶段对应，如 {\"thesis\":\"核心逻辑\",\"valuationLow\":12.5}") String draftJson) {
        try {
            ResearchDraftSpec spec = buildDraftSpec(stage, draftJson);
            return draftSummary(spec) + "\n```research-draft\n" + mapper.writeValueAsString(spec) + "\n```";
        } catch (Exception e) {
            // 参数错误兜底：绝不抛异常打断会话，错误文本交还 LLM 自行修正重试
            String reason = e.getMessage() == null ? "无法解析草稿参数" : e.getMessage();
            log.warn("research_draft 参数错误: stage={}, reason={}", stage, reason);
            return "[research_draft] 参数错误：" + reason;
        }
    }

    /** 解析 draftJson 并按 stage 构造对应变体：字段缺失容忍（null 允许），类型错/未知 stage 抛 IAE 由调用方兜底。 */
    private ResearchDraftSpec buildDraftSpec(String stage, String draftJson) throws Exception {
        String normalized = stage == null ? "" : stage.trim();
        if (draftJson == null || draftJson.isBlank()) {
            throw new IllegalArgumentException("draftJson 不能为空");
        }
        JsonNode root = mapper.readTree(draftJson);
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("draftJson 须为 JSON 对象");
        }
        return switch (normalized) {
            case "NEW_ANALYSIS" -> ResearchDraftSpec.analysis(
                    text(root, "symbol"), text(root, "companyName"), text(root, "industry"),
                    textList(root, "checklistDone"), text(root, "summary"));
            case "STRATEGY" -> ResearchDraftSpec.strategy(
                    decimal(root, "valuationLow"), decimal(root, "valuationHigh"),
                    text(root, "thesis"), text(root, "positionPlan"), text(root, "buyConditions"),
                    falsifierItems(root));
            case "POSITION" -> ResearchDraftSpec.entryPlan(
                    batchItems(root), decimal(root, "winRate"), decimal(root, "payoffRatio"),
                    text(root, "note"));
            case "REVIEW" -> ResearchDraftSpec.review(
                    text(root, "tier"), text(root, "periodStart"), text(root, "periodEnd"),
                    text(root, "narrative"));
            default -> throw new IllegalArgumentException(
                    "未知 stage: " + normalized + "（合法值：NEW_ANALYSIS/STRATEGY/POSITION/REVIEW）");
        };
    }

    /** 各阶段一句话摘要（进 LLM/stateStore），null 字段静默跳过。 */
    private static String draftSummary(ResearchDraftSpec spec) {
        return switch (spec) {
            case ResearchDraftSpec.AnalysisDraft a -> {
                StringJoiner s = new StringJoiner("，");
                String head = joinSpace(a.symbol(), a.companyName(), a.industry());
                if (!head.isEmpty()) {
                    s.add(head);
                }
                if (a.checklistDone() != null) {
                    s.add("清单 " + a.checklistDone().size() + " 项");
                }
                yield summaryDone("分析草稿", s);
            }
            case ResearchDraftSpec.StrategyDraft d -> {
                StringJoiner s = new StringJoiner("，");
                String range = joinTilde(plain(d.valuationLow()), plain(d.valuationHigh()));
                if (!range.isEmpty()) {
                    s.add("估值区间 " + range);
                }
                if (d.riskItems() != null) {
                    s.add("证伪条件 " + d.riskItems().size() + " 条");
                }
                yield summaryDone("策略草稿", s);
            }
            case ResearchDraftSpec.EntryPlanDraft e -> {
                StringJoiner s = new StringJoiner("，");
                if (e.batches() != null) {
                    s.add(e.batches().size() + " 批建仓");
                }
                if (e.winRate() != null) {
                    s.add("胜率 " + e.winRate().toPlainString());
                }
                if (e.payoffRatio() != null) {
                    s.add("盈亏比 " + e.payoffRatio().toPlainString());
                }
                yield summaryDone("建仓草稿", s);
            }
            case ResearchDraftSpec.ReviewDraft r -> {
                StringJoiner s = new StringJoiner("，");
                if (r.tier() != null) {
                    s.add("档位 " + r.tier());
                }
                String period = joinTilde(r.periodStart(), r.periodEnd());
                if (!period.isEmpty()) {
                    s.add("复盘区间 " + period);
                }
                yield summaryDone("复盘草稿", s);
            }
        };
    }

    private static String summaryDone(String label, StringJoiner s) {
        return s.length() == 0 ? label + "已回显" : label + "已回显：" + s;
    }

    /** 非空段以空格拼接（全空返回空串）。 */
    private static String joinSpace(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(p);
            }
        }
        return sb.toString();
    }

    /** 两侧任一存在即以 ~ 连接（全空返回空串）。 */
    private static String joinTilde(String left, String right) {
        if (left == null || left.isBlank()) {
            return right == null ? "" : right;
        }
        return right == null || right.isBlank() ? left : left + "~" + right;
    }

    private static String plain(BigDecimal v) {
        return v == null ? null : v.toPlainString();
    }

    /** 字符串字段：缺失/null 容忍，非文本节点为类型错。 */
    private static String text(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isTextual()) {
            throw new IllegalArgumentException("字段 " + field + " 须为字符串");
        }
        return n.asText();
    }

    /** 数字字段：接受 JSON 数值与数值文本，无法解析为 BigDecimal 即类型错。 */
    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        try {
            return new BigDecimal(n.asText());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("字段 " + field + " 须为数字");
        }
    }

    /** 整数字段：缺失默认 0，非整数即类型错。 */
    private static long longValue(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) {
            return 0L;
        }
        try {
            return new BigDecimal(n.asText()).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("字段 " + field + " 须为整数");
        }
    }

    private static List<String> textList(JsonNode root, String field) {
        JsonNode arr = arrayNode(root, field);
        if (arr == null) {
            return null;
        }
        List<String> out = new ArrayList<>(arr.size());
        for (JsonNode el : arr) {
            if (!el.isTextual()) {
                throw new IllegalArgumentException("字段 " + field + " 的元素须为字符串");
            }
            out.add(el.asText());
        }
        return out;
    }

    private static List<ResearchDraftSpec.FalsifierItem> falsifierItems(JsonNode root) {
        JsonNode arr = arrayNode(root, "riskItems");
        if (arr == null) {
            return null;
        }
        List<ResearchDraftSpec.FalsifierItem> items = new ArrayList<>(arr.size());
        for (JsonNode el : arr) {
            requireObject(el, "riskItems");
            items.add(new ResearchDraftSpec.FalsifierItem(
                    text(el, "kind"), text(el, "predicate"), decimal(el, "threshold"), text(el, "note")));
        }
        return items;
    }

    private static List<ResearchDraftSpec.BatchItem> batchItems(JsonNode root) {
        JsonNode arr = arrayNode(root, "batches");
        if (arr == null) {
            return null;
        }
        List<ResearchDraftSpec.BatchItem> items = new ArrayList<>(arr.size());
        for (JsonNode el : arr) {
            requireObject(el, "batches");
            items.add(new ResearchDraftSpec.BatchItem(
                    decimal(el, "priceLow"), decimal(el, "priceHigh"), longValue(el, "quantity"),
                    decimal(el, "ratio")));
        }
        return items;
    }

    /** 数组字段：缺失/null 容忍，非数组为类型错。 */
    private static JsonNode arrayNode(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isArray()) {
            throw new IllegalArgumentException("字段 " + field + " 须为数组");
        }
        return n;
    }

    private static void requireObject(JsonNode el, String field) {
        if (!el.isObject()) {
            throw new IllegalArgumentException("字段 " + field + " 的元素须为 JSON 对象");
        }
    }

    private String run(JsonSupplier supplier) {
        try {
            return supplier.get();
        } catch (MarketDataException e) {
            log.warn("工具数据获取失败: code={}, msg={}", e.getCode(), e.getMessage());
            return ToolResultBlocks.toError(mapper, e.getMessage(), "数据源暂不可用，请稍后重试或换个问法");
        } catch (Exception e) {
            log.error("工具执行异常", e);
            return ToolResultBlocks.toError(mapper, "工具执行失败", "请稍后重试");
        }
    }

    /** 双通道版 run()：失败不 emit，返回错误 JSON 文本（前端 ChartCard 嗅探降级）。共享自 ToolResultBlocks。 */
    private ToolResultBlock runBlock(ToolResultBlocks.BlockSupplier supplier) {
        return ToolResultBlocks.runBlock(log, mapper, supplier);
    }

    @FunctionalInterface
    private interface JsonSupplier {
        String get() throws Exception;
    }
}
