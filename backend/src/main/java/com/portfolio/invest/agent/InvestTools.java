package com.portfolio.invest.agent;

import com.portfolio.invest.agent.tools.FinancialTools;
import com.portfolio.invest.agent.tools.IndustryTools;
import com.portfolio.invest.agent.tools.IntelligenceTools;
import com.portfolio.invest.agent.tools.MarketTools;
import com.portfolio.invest.agent.tools.ResearchDraftTools;
import com.portfolio.invest.agent.tools.ScreeningTools;
import com.portfolio.invest.agent.tools.ToolResultBlocks;
import com.portfolio.invest.agent.tools.ValuationTools;
import com.portfolio.invest.application.industry.IndustryApplicationService;
import com.portfolio.invest.application.intelligence.IntelligenceQueryService;
import com.portfolio.invest.application.market.FinancialQueryService;
import com.portfolio.invest.application.market.MarketDataService;
import com.portfolio.invest.application.screening.ScreeningApplicationService;
import com.portfolio.invest.application.valuation.ValuationApplicationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolEmitter;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 投研 Agent 的数据工具门面（返回 JSON 文本；失败返回结构化错误（不抛异常））。
 *
 * <p>2026-10-12 按域拆分子组件（审查 §三.9）：原 712 行 13 @Tool 单类的方法体纯搬移至
 * {@code agent.tools} 子包——{@link MarketTools}（search_stock/get_quote/get_kline/get_news/
 * get_market_overview）、{@link ValuationTools}（get_valuation）、{@link ScreeningTools}
 * （screen_stocks）、{@link FinancialTools}（get_financials/analyze_financials）、
 * {@link IndustryTools}（analyze_industry）、{@link IntelligenceTools}（search_news/macro_brief）、
 * {@link ResearchDraftTools}（research_draft）。本门面保留全部 @Tool 注解与方法签名
 * （工具名/描述/参数字节不变，UserToolkitFactory 装配与 eval 资产 hash 对账不受影响），
 * 方法体为一行委托；错误兜底经 {@link ToolResultBlocks}（可见性随子包化放宽为 public，
 * 语义不变）。与 {@link UserInvestTools}（用户态工具）的拆分先例一致。
 */
@Component
public class InvestTools {

    private final MarketTools marketTools;
    private final ValuationTools valuationTools;
    private final ScreeningTools screeningTools;
    private final FinancialTools financialTools;
    private final IndustryTools industryTools;
    private final IntelligenceTools intelligenceTools;
    private final ResearchDraftTools researchDraftTools;

    public InvestTools(
            MarketDataService market,
            ValuationApplicationService valuationApplicationService,
            ScreeningApplicationService screening,
            FinancialQueryService financialQuery,
            IndustryApplicationService industry,
            IntelligenceQueryService intelligenceQuery,
            ObjectMapper mapper,
            com.portfolio.invest.config.InvestProperties properties) {
        this.marketTools = new MarketTools(market, mapper);
        this.valuationTools = new ValuationTools(valuationApplicationService, mapper);
        this.screeningTools = new ScreeningTools(screening, mapper);
        this.financialTools = new FinancialTools(market, financialQuery, mapper);
        this.industryTools = new IndustryTools(industry, mapper);
        this.intelligenceTools = new IntelligenceTools(intelligenceQuery, mapper);
        this.researchDraftTools = new ResearchDraftTools(mapper, properties);
    }

    @Tool(
            name = "search_stock",
            description = "按股票名称或代码模糊搜索A股，返回候选列表（代码、名称、市场）。用户提到股票名称时先调用本工具获取精确代码。",
            readOnly = true,
            concurrencySafe = true)
    public String searchStock(
            @ToolParam(name = "query", description = "股票名称或代码关键词，如“茅台”或“600519”") String query) {
        return marketTools.searchStock(query);
    }

    @Tool(
            name = "get_quote",
            description = "获取个股实时行情：最新价、涨跌幅、成交量额、高低开、市盈率、市净率等。",
            readOnly = true,
            concurrencySafe = true)
    public String getQuote(
            @ToolParam(name = "code", description = "6位A股代码，如 600519") String code) {
        return marketTools.getQuote(code);
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
        return marketTools.getKline(code, period, limit, emitter);
    }

    @Tool(
            name = "get_financials",
            description = "获取个股核心财务指标：每股收益、每股净资产、营收、净利润、加权ROE、毛利率，以及当前市盈率/市净率。",
            readOnly = true,
            concurrencySafe = true)
    public ToolResultBlock getFinancials(
            @ToolParam(name = "code", description = "6位A股代码，如 600519") String code,
            ToolEmitter emitter) {
        return financialTools.getFinancials(code, emitter);
    }

    @Tool(
            name = "get_news",
            description = "获取个股近期新闻（标题、摘要、来源、时间），用于消息面分析。",
            readOnly = true,
            concurrencySafe = true)
    public String getNews(
            @ToolParam(name = "code", description = "6位A股代码，如 600519") String code,
            @ToolParam(name = "limit", description = "返回条数，默认 10，最大 20") Integer limit) {
        return marketTools.getNews(code, limit);
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
        return intelligenceTools.searchNews(q, stock, industry, from, to, minImportance, limit);
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
        return intelligenceTools.macroBrief(indicators, policyDays);
    }

    @Tool(
            name = "get_market_overview",
            description = "获取A股大盘速览：上证指数、深证成指、创业板指的最新点位与涨跌幅。",
            readOnly = true,
            concurrencySafe = true)
    public ToolResultBlock getMarketOverview(ToolEmitter emitter) {
        return marketTools.getMarketOverview(emitter);
    }

    @Tool(
            name = "get_valuation",
            description = "查询市场估值：全A股PE/PB中位数及历史分位、股债利差(ERP)、主要指数估值、市场情绪温度计。用于回答「现在市场贵不贵/估值高不高」类问题。",
            readOnly = true,
            concurrencySafe = true)
    public ToolResultBlock getValuation(ToolEmitter emitter) {
        return valuationTools.getValuation(emitter);
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
        return screeningTools.screenStocks(peTtmMax, pbMax, dividendYieldMin, roeMin, roaMin,
                grossMarginMin, debtToAssetsMax, currentRatioMin, revenueYoyMin, netprofitYoyMin,
                totalMvMin, turnoverRateMin, industryCode, indexCode, sortBy, sortDirection, limit, emitter);
    }

    @Tool(
            name = "analyze_financials",
            description = "财报解读：杜邦三因子拆解（净利率×总资产周转率×权益乘数）+ 近 12 季趋势表（ROE/ROA/毛利率/资产负债率/营收及同比）。用户问「XX 赚钱能力如何/财报怎么样」时调用；股票名称先用 search_stock 换码。",
            readOnly = true,
            concurrencySafe = true)
    public ToolResultBlock analyzeFinancials(
            @ToolParam(name = "code", description = "6位A股代码，如 600519") String code,
            ToolEmitter emitter) {
        return financialTools.analyzeFinancials(code, emitter);
    }

    @Tool(
            name = "analyze_industry",
            description = "行业分析：不传行业码返回全行业估值板面（PE/PB 及历史分位排名）；传申万一级码（如 801780 银行）返回该行业估值位 + 头部企业表。用户问「XX 行业怎么样/哪些行业便宜」时调用；行业名先经无参板面对齐行业码再下钻。",
            readOnly = true,
            concurrencySafe = true)
    public ToolResultBlock analyzeIndustry(
            @ToolParam(name = "industryCode", description = "申万一级行业码，可空（空=返回全行业板面）") String industryCode,
            ToolEmitter emitter) {
        return industryTools.analyzeIndustry(industryCode, emitter);
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
        return researchDraftTools.researchDraft(stage, draftJson);
    }
}
