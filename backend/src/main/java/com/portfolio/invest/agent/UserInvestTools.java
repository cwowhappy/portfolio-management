package com.portfolio.invest.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.agent.chart.ChartSpecs;
import com.portfolio.invest.application.allocation.AllocationApplicationService;
import com.portfolio.invest.application.intelligence.AnnouncementScope;
import com.portfolio.invest.application.intelligence.AnnouncementSearchFilter;
import com.portfolio.invest.application.intelligence.IntelligenceQueryService;
import com.portfolio.invest.application.portfolio.PortfolioApplicationService;
import com.portfolio.invest.domain.intelligence.AnnouncementType;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolEmitter;
import io.agentscope.core.tool.ToolParam;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用户态投研工具（F09/F10 + MS-21 F09 公告检索）：userId 经 UserToolkitFactory 构造注入，
 * 不进 LLM 上下文（@ToolParam 永不出现 userId）。非 Spring bean（per-会话实例）；
 * 错误兜底经 ToolResultBlocks 共享（block 版与 String 版）。
 */
public class UserInvestTools {

    private static final Logger log = LoggerFactory.getLogger(UserInvestTools.class);

    /** search_announcements 空结果话术：区分「该标的无公告」与「检索无结果」两态（scope 空集话术由服务层给）。 */
    static final String STOCK_EMPTY_MESSAGE = "该标的无公告（可放宽日期区间、类型或关键词后重试）";
    static final String NO_RESULT_MESSAGE = "检索无结果（可调整关键词、类型或日期区间后重试）";

    private final Long userId;
    private final PortfolioApplicationService portfolio;
    private final AllocationApplicationService allocation;
    private final IntelligenceQueryService intelligenceQuery;
    private final ObjectMapper mapper;

    public UserInvestTools(Long userId, PortfolioApplicationService portfolio,
                           AllocationApplicationService allocation,
                           IntelligenceQueryService intelligenceQuery, ObjectMapper mapper) {
        this.userId = userId;
        this.portfolio = portfolio;
        this.allocation = allocation;
        this.intelligenceQuery = intelligenceQuery;
        this.mapper = mapper;
    }

    @Tool(
            name = "analyze_portfolio",
            description = "读取当前用户持仓组合：总览（总资产/成本/浮动盈亏/持仓数）、资产配置饼图、行业分布、集中度（前五大占比）。用户问「我的持仓/组合怎么样、仓位结构如何」时调用。数据为用户私有，仅本人可见。",
            readOnly = true,
            concurrencySafe = true)
    public ToolResultBlock analyzePortfolio(ToolEmitter emitter) {
        return runBlock(() -> {
            var overview = portfolio.overview(userId);
            if (overview.positionCount() == 0) {
                return ToolResultBlock.text("暂无持仓。可到持仓页添加，或让我介绍怎么开始建立组合。");
            }
            var assetAllocation = portfolio.allocation(userId);
            var concentration = portfolio.concentration(userId);
            var industryDistribution = portfolio.industryDistribution(userId);
            emitter.emit(ToolResultBlock.builder()
                    .output(TextBlock.builder().text(mapper.writeValueAsString(
                            ChartSpecs.portfolioPie(assetAllocation))).build())
                    .build());
            return ToolResultBlock.text(
                    ChartSpecs.portfolioSummary(overview, concentration, industryDistribution));
        });
    }

    @Tool(
            name = "suggest_allocation",
            description = "资产配置建议：读取用户风险测评结果（无则引导先完成测评），给出推荐配置（五档风险画像内置权重：保守/稳健/平衡/成长/进取）与当前资产分布对照表及偏离；有生效配置方案时对照方案权重。用户问「我该怎么配置/建议仓位/资产怎么配」时调用。推荐权重来自 M07 问卷评分模型，非本工具编造。",
            readOnly = true,
            concurrencySafe = true)
    public ToolResultBlock suggestAllocation(ToolEmitter emitter) {
        return runBlock(() -> {
            var assessment = allocation.latestAssessment(userId);
            if (assessment.isEmpty()) {
                return ToolResultBlock.text(
                        "尚未完成风险测评。请先到配置页完成 M07 风险测评问卷，我再基于你的风险画像给出配置建议（不凭空编造建议）。");
            }
            var a = assessment.get();
            var deviation = allocation.deviation(userId); // 无生效方案返回空列表（不抛）
            emitter.emit(ToolResultBlock.builder()
                    .output(TextBlock.builder().text(mapper.writeValueAsString(
                            deviation.slices().isEmpty()
                                    // 无方案：测评推荐权重 vs 当前分布（此时才读一次持仓分布）
                                    ? ChartSpecs.allocationDeviationTable(a.profileName(), a.weights(),
                                            portfolio.allocation(userId))
                                    // 有方案：直接用 deviation（service 内部已取当前分布，避免重复全量持仓读取）
                                    : ChartSpecs.allocationDeviationTable(a.profileName(), deviation))).build())
                    .build());
            return ToolResultBlock.text(ChartSpecs.allocationSummary(a, deviation));
        });
    }

    @Tool(
            name = "search_announcements",
            description = "检索上市公司公告情报：按标的/公告类型/日期区间/关键词组合过滤，条目含公告类型标签、"
                    + "六字段业绩要点（营收/归母净利润/净利同比/扣非/毛利率/分红——未披露字段不编造）与 PDF 原文链接。"
                    + "用户问某标的的公告、业绩预告/快报、回购、增持减持、股权激励等重大事项时调用；"
                    + "查「我的订阅的公告」传 scope=subscription、「我的持仓的公告」传 scope=holdings（缺省 all 全库检索）。",
            readOnly = true,
            concurrencySafe = true)
    public String searchAnnouncements(
            @ToolParam(name = "stock", description = "标的代码，如 600519，可空") String stock,
            @ToolParam(name = "type", description = "公告类型枚举名：INCREASE_HOLD(股东增持)/DECREASE_HOLD(股东减持)"
                    + "/BUYBACK(股份回购)/PLACEMENT(定增配股)/RELATED_TRANSACTION(关联交易)/EARNINGS_FORECAST(业绩预告)"
                    + "/EARNINGS_FLASH(业绩快报)/PERIODIC_REPORT(定期报告)/EQUITY_INCENTIVE(股权激励)"
                    + "/DELISTING_RISK(退市风险)/OTHER(其他)，可空") String type,
            @ToolParam(name = "from", description = "起始日期 yyyy-MM-dd（含），可空") String from,
            @ToolParam(name = "to", description = "结束日期 yyyy-MM-dd（含），可空") String to,
            @ToolParam(name = "q", description = "关键词，按标题近似匹配，如“回购”，可空") String q,
            @ToolParam(name = "scope", description = "检索范围：all 全库（默认）/ subscription 我的订阅标的 / holdings 我的建仓持仓项目标的") String scope,
            @ToolParam(name = "limit", description = "返回条数，默认 10，最大 20") Integer limit) {
        // 日期前置校验：格式错走参数错误（run 兜底会误导为「工具执行失败」）
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
        // 类型前置校验：未知枚举走参数错误并列出合法值（LLM 自纠）；静默忽略会歪曲过滤结果
        AnnouncementType typeEnum;
        try {
            typeEnum = parseType(type);
        } catch (IllegalArgumentException e) {
            return ToolResultBlocks.toError(mapper,
                    "未知公告类型：" + type + "，合法取值：" + Arrays.toString(AnnouncementType.values()),
                    "请修正 type 后重试");
        }
        return run(() -> {
            IntelligenceQueryService.AnnouncementSearchResult result =
                    intelligenceQuery.searchAnnouncements(userId, new AnnouncementSearchFilter(
                            q, stock, typeEnum, fromDate, toDate, AnnouncementScope.parse(scope), limit));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("items", result.items());
            if (result.scopeMessage() != null) {
                // scope 未配置/标的不在范围：服务层引导语原样透传（区分于「检索无结果」）
                body.put("message", result.scopeMessage());
            } else if (result.items().isEmpty()) {
                body.put("message", stock != null && !stock.isBlank()
                        ? STOCK_EMPTY_MESSAGE : NO_RESULT_MESSAGE);
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

    /** 空白串归一 null；未知枚举名抛 IllegalArgumentException 由调用方前置校验兜底。 */
    private static AnnouncementType parseType(String s) {
        return s == null || s.isBlank() ? null : AnnouncementType.valueOf(s.trim());
    }

    /** 共享兜底（ToolResultBlocks，与 InvestTools 同款）；用户态额外带 userId 上下文日志。 */
    private ToolResultBlock runBlock(ToolResultBlocks.BlockSupplier supplier) {
        return ToolResultBlocks.runBlock(log, mapper, supplier);
    }

    /** String 返回版共享兜底（照 InvestTools.run 同款形状）。 */
    private String run(ToolResultBlocks.StringSupplier supplier) {
        return ToolResultBlocks.runString(log, mapper, supplier);
    }
}
