package com.portfolio.invest.agent.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.agent.InvestTools;
import com.portfolio.invest.agent.UserInvestTools;
import com.portfolio.invest.agent.trust.NumberExtractor;
import com.portfolio.invest.agent.trust.NumberToken;
import com.portfolio.invest.application.allocation.AllocationApplicationService;
import com.portfolio.invest.application.intelligence.IntelligenceQueryService;
import com.portfolio.invest.application.portfolio.PortfolioApplicationService;
import com.portfolio.invest.application.research.ReportAnchorRecheckPort;
import com.portfolio.invest.application.research.ReportStalenessService;
import com.portfolio.invest.application.wiki.WikiApplicationService;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.research.ReportAnchor;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolEmitter;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 研报锚点重查器（MS-32 B12，设计规格 §3.8）：{@link ReportAnchorRecheckPort} 的
 * agent 侧实现——按锚点 {@code toolName} 白名单直调 {@link InvestTools} /
 * {@link UserInvestTools} 实例方法（与 Toolkit 注册实例共享，B0 探针 #4 已证无状态可重入；
 * 直调绕过 RecordingAgentToolDecorator——不污染任何真值池），全程只读不回写锚点行。
 *
 * <p><strong>emitter null 安全核查结论（本任务 Step 1 必做项）：</strong>ToolResultBlock 系
 * 六工具（get_kline/get_financials/get_market_overview/get_valuation/analyze_financials/
 * analyze_industry）在非空路径对 {@code emitter.emit(...)} <strong>无 null 守卫</strong>
 * （InvestTools.java:121/145/281/305/398/421 一致），直调传 null 必 NPE；ToolEmitter 为
 * 单方法函数式接口，本类以 {@link #NOOP_EMITTER} 空 lambda 安全吸收——emit 的图表 spec
 * 本就只走 SSE 前端通道、永不进 LLM/锚定文本，丢弃即口径无损。
 *
 * <p><strong>四态判定（顺序）：</strong>现值文本 {@link NumberExtractor#extract(String)}
 * （对话档默认模式——重查结果非假设章节无列表序号）的数据性数值集中，存在与锚定值
 * （snippet 提取，与 B7 绑定口径一致）满足 {@code |现值−锚定| ≤ changedRelative×|锚定|}
 * （含端；锚定为 0 退化为精确等值）的现值 → FRESH；否则 CHANGED；asOfKind=DATA 且 asOf
 * 距今超 trust.confidence.stale-* 自然日线（严格大于；族划分同 ConfidenceScorer：行情族
 * 1 天 / 财报族 110 天 / 宏观族 35 天，未归族工具无 stale 线）→ STALE——与 CHANGED 并存时
 * CHANGED 优先；GENERATED/CALL 无数据时点语义不参与 STALE（决策 #17），asOf null 亦只比对。
 * 白名单外工具 / args 缺参错型 / 工具失败信封（{"error":...}——InvestTools 失败不抛恒返
 * 错误信封）/ 调用异常 / 锚定片段无数据性数字 → UNVERIFIABLE（非抛，护栏哲学：
 * 宁可标不可验不可断 detail）。
 */
@Component
public class ReportAnchorRechecker implements ReportAnchorRecheckPort {

    private static final Logger log = LoggerFactory.getLogger(ReportAnchorRechecker.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 直调用 no-op 发射器：ToolResultBlock 系工具非空路径必 emit（实现无 null 守卫），
     * 传 null 必 NPE；函数式接口以空 lambda 安全吸收（图表 spec 不进重查比对，丢弃无损）。
     */
    private static final ToolEmitter NOOP_EMITTER = block -> { };

    /** 数据类别（陈旧族；同 ConfidenceScorer 工具归类取同款，未归族 = 无 stale 线仅比对）。 */
    private enum Family { QUOTES, FINANCIALS, MACRO }

    private static final Map<String, Family> TOOL_FAMILY = Map.of(
            "get_quote", Family.QUOTES,
            "get_kline", Family.QUOTES,
            "get_market_overview", Family.QUOTES,
            "get_financials", Family.FINANCIALS,
            "analyze_financials", Family.FINANCIALS,
            "macro_brief", Family.MACRO);

    private final InvestTools investTools;
    private final PortfolioApplicationService portfolio;
    private final AllocationApplicationService allocation;
    private final IntelligenceQueryService intelligenceQuery;
    private final WikiApplicationService wiki;
    private final ObjectMapper mapper;
    private final InvestProperties properties;
    private final Clock clock;

    /** 主构造器（@Autowired 显式标注：存在注入时钟的重载构造器时须指定装配入口，照 MarketSnapshotAssembler 先例）。 */
    @Autowired
    public ReportAnchorRechecker(InvestTools investTools, PortfolioApplicationService portfolio,
                                 AllocationApplicationService allocation,
                                 IntelligenceQueryService intelligenceQuery,
                                 WikiApplicationService wiki, ObjectMapper mapper,
                                 InvestProperties properties) {
        this(investTools, portfolio, allocation, intelligenceQuery, wiki,
                mapper, properties, Clock.system(ZONE));
    }

    /** 测试注入：固定时钟（STALE 自然日语义确定性，禁真实墙钟）。 */
    public ReportAnchorRechecker(InvestTools investTools, PortfolioApplicationService portfolio,
                                 AllocationApplicationService allocation,
                                 IntelligenceQueryService intelligenceQuery,
                                 WikiApplicationService wiki, ObjectMapper mapper,
                                 InvestProperties properties, Clock clock) {
        this.investTools = investTools;
        this.portfolio = portfolio;
        this.allocation = allocation;
        this.intelligenceQuery = intelligenceQuery;
        this.wiki = wiki;
        this.mapper = mapper;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public ReportStalenessService.AnchorStatus recheck(Long userId, ReportAnchor anchor) {
        BigDecimal anchored = anchoredValue(anchor);
        if (anchored == null) {
            return unverifiable(anchor); // 锚定片段无数据性数字——无从比对
        }
        String currentText;
        try {
            currentText = dispatch(userId, anchor.toolName(), anchor.args());
        } catch (Exception e) {
            log.debug("锚点重查派发失败: tool={}, anchorId={}, reason={}",
                    anchor.toolName(), anchor.id(), e.getMessage());
            return unverifiable(anchor);
        }
        if (currentText == null || isErrorEnvelope(currentText)) {
            return unverifiable(anchor); // 白名单外 / 工具失败信封
        }
        List<BigDecimal> currents = NumberExtractor.extract(currentText).stream()
                .filter(NumberToken::dataLike)
                .map(NumberToken::value)
                .toList();
        BigDecimal best = bestMatch(anchored, currents);
        if (best != null && withinTolerance(anchored, best)) {
            return new ReportStalenessService.AnchorStatus(anchor.id(),
                    staleBeyondLine(anchor) ? ReportStalenessService.AnchorState.STALE
                            : ReportStalenessService.AnchorState.FRESH,
                    best.toPlainString());
        }
        return new ReportStalenessService.AnchorStatus(anchor.id(),
                ReportStalenessService.AnchorState.CHANGED,
                best == null ? null : best.toPlainString());
    }

    /**
     * 白名单派发（§3.8 v1 十一工具）：args 反构造按各工具 @ToolParam 签名取参
     * （类型防御：缺参/错型抛出由 {@link #recheck} 收敛 UNVERIFIABLE）。
     * 返回 null = 白名单外工具；用户态两工具经 per-call {@link UserInvestTools} 轻实例
     * （构造注入 userId，沿 B0 探针 #4 回退口径——无状态，成本零）。
     */
    private String dispatch(Long userId, String tool, Map<String, Object> args) {
        Map<String, Object> a = args == null ? Map.of() : args;
        return switch (tool == null ? "" : tool) {
            case "get_quote" -> investTools.getQuote(requiredString(a, "code"));
            case "get_kline" -> textOf(investTools.getKline(requiredString(a, "code"),
                    optionalString(a, "period", null), optionalInt(a, "limit"), NOOP_EMITTER));
            case "get_financials" ->
                    textOf(investTools.getFinancials(requiredString(a, "code"), NOOP_EMITTER));
            case "get_market_overview" -> textOf(investTools.getMarketOverview(NOOP_EMITTER));
            case "get_valuation" -> textOf(investTools.getValuation(NOOP_EMITTER));
            case "analyze_financials" ->
                    textOf(investTools.analyzeFinancials(requiredString(a, "code"), NOOP_EMITTER));
            case "analyze_industry" ->
                    textOf(investTools.analyzeIndustry(optionalString(a, "industryCode", null), NOOP_EMITTER));
            case "macro_brief" -> investTools.macroBrief(
                    optionalString(a, "indicators", null), optionalInt(a, "policyDays"));
            case "search_news" -> investTools.searchNews(
                    optionalString(a, "q", null), optionalString(a, "stock", null),
                    optionalString(a, "industry", null), optionalString(a, "from", null),
                    optionalString(a, "to", null), optionalInt(a, "minImportance"),
                    optionalInt(a, "limit"));
            case "search_announcements" -> userTools(userId).searchAnnouncements(
                    optionalString(a, "stock", null), optionalString(a, "type", null),
                    optionalString(a, "from", null), optionalString(a, "to", null),
                    optionalString(a, "q", null), optionalString(a, "scope", null),
                    optionalInt(a, "limit"));
            case "search_wiki" -> userTools(userId).searchWiki(
                    optionalString(a, "keyword", null), optionalString(a, "type", null));
            default -> null;
        };
    }

    /** 用户态轻实例（search_announcements/search_wiki 的 userId 载体；per-call 构造，无状态成本零）。 */
    private UserInvestTools userTools(Long userId) {
        return new UserInvestTools(userId, portfolio, allocation, intelligenceQuery, wiki, mapper);
    }

    /**
     * 工具失败信封嗅探：{"error":...} JSON 对象（InvestTools/UserInvestTools 失败不抛、
     * 恒返错误信封——前端 ChartCard 嗅探降级同款契约）；非 JSON 文本（纯文本摘要）非信封。
     */
    private boolean isErrorEnvelope(String text) {
        try {
            JsonNode node = mapper.readTree(text);
            return node != null && node.isObject() && node.hasNonNull("error");
        } catch (Exception e) {
            return false;
        }
    }

    /** 块文本拼接：output 里的 TextBlock 依次连接（与 LLM 看到的文本通道一致——同 RecordingAgentToolDecorator.textOf 口径，其为本包私有故就地复刻并注源）。 */
    private static String textOf(ToolResultBlock block) {
        if (block == null || block.getOutput() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : block.getOutput()) {
            if (b instanceof TextBlock t && t.getText() != null) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(t.getText());
            }
        }
        return sb.toString();
    }

    /** 锚定数值：snippet 提取（与 B7 绑定口径一致）首个数据性 token；无 → null（无法比对）。 */
    private static BigDecimal anchoredValue(ReportAnchor anchor) {
        return NumberExtractor.extract(anchor.snippet()).stream()
                .filter(NumberToken::dataLike)
                .map(NumberToken::value)
                .findFirst().orElse(null);
    }

    /** 现值集合中与锚定值偏差最小者（空集 → null；并列取首现保稳定）。 */
    private static BigDecimal bestMatch(BigDecimal anchored, List<BigDecimal> currents) {
        BigDecimal best = null;
        for (BigDecimal c : currents) {
            if (best == null || c.subtract(anchored).abs().compareTo(best.subtract(anchored).abs()) < 0) {
                best = c;
            }
        }
        return best;
    }

    /** 容差判定（含端，恰 changedRelative 过）：|现值−锚定| ≤ changedRelative×|锚定|（锚定 0 退化为精确等值）。 */
    private boolean withinTolerance(BigDecimal anchored, BigDecimal current) {
        BigDecimal diff = current.subtract(anchored).abs();
        BigDecimal floor = anchored.abs()
                .multiply(properties.getReport().getStaleness().changedRelative());
        return diff.compareTo(floor) <= 0;
    }

    /** STALE 判定：asOfKind=DATA 且 asOf 距今自然日数严格大于族 stale 线；GENERATED/CALL/未归族/null 恒 false。 */
    private boolean staleBeyondLine(ReportAnchor anchor) {
        if (anchor.asOf() == null || !"DATA".equals(anchor.asOfKind())) {
            return false;
        }
        Family family = anchor.toolName() == null ? null : TOOL_FAMILY.get(anchor.toolName());
        if (family == null) {
            return false;
        }
        long age = ChronoUnit.DAYS.between(
                LocalDate.ofInstant(anchor.asOf(), clock.getZone()), LocalDate.now(clock));
        InvestProperties.Trust.ConfidenceSettings confidence = properties.getTrust().getConfidence();
        return switch (family) {
            case QUOTES -> age > confidence.staleQuoteDays();
            case FINANCIALS -> age > confidence.staleFinancialDays();
            case MACRO -> age > confidence.staleMacroDays();
        };
    }

    private static ReportStalenessService.AnchorStatus unverifiable(ReportAnchor anchor) {
        return new ReportStalenessService.AnchorStatus(
                anchor.id(), ReportStalenessService.AnchorState.UNVERIFIABLE, null);
    }

    /** 必填字符串参数：缺失/空白/非字符串 → 抛（由 recheck 收敛 UNVERIFIABLE）。 */
    private static String requiredString(Map<String, Object> args, String key) {
        String value = optionalString(args, key, null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("参数 " + key + " 缺失或为空");
        }
        return value;
    }

    /** 可选字符串参数：缺失/null → 缺省值；非 null 非字符串 → 抛（错型防御）。 */
    private static String optionalString(Map<String, Object> args, String key, String dft) {
        Object v = args.get(key);
        if (v == null) {
            return dft;
        }
        if (!(v instanceof String s)) {
            throw new IllegalArgumentException(
                    "参数 " + key + " 须为字符串，实际 " + v.getClass().getSimpleName());
        }
        return s;
    }

    /** 可选整数参数（Number 族兼容 JSON 还原的 Integer/Double）；非数值 → 抛。 */
    private static Integer optionalInt(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        throw new IllegalArgumentException(
                "参数 " + key + " 须为数值，实际 " + v.getClass().getSimpleName());
    }
}
