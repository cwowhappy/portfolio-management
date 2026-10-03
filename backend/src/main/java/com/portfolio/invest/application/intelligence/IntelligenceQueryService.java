package com.portfolio.invest.application.intelligence;

import com.portfolio.invest.application.research.IntelligenceSubscriptionHook;
import com.portfolio.invest.application.research.IntelligenceSubscriptionHook.IntelligenceTarget;
import com.portfolio.invest.domain.intelligence.AnnouncementMetrics;
import com.portfolio.invest.domain.intelligence.AnnouncementRecord;
import com.portfolio.invest.domain.intelligence.AnnouncementRepository;
import com.portfolio.invest.domain.intelligence.AnnouncementType;
import com.portfolio.invest.domain.intelligence.Direction;
import com.portfolio.invest.domain.intelligence.IntelligenceSubscription;
import com.portfolio.invest.domain.intelligence.MacroPoint;
import com.portfolio.invest.domain.intelligence.NewsRecord;
import com.portfolio.invest.domain.intelligence.NewsRepository;
import com.portfolio.invest.domain.intelligence.PageQuery;
import com.portfolio.invest.domain.intelligence.PageResult;
import com.portfolio.invest.domain.intelligence.PolicyConfidence;
import com.portfolio.invest.domain.intelligence.PolicyDirection;
import com.portfolio.invest.domain.intelligence.PolicyEvent;
import com.portfolio.invest.domain.intelligence.PolicyRepository;
import com.portfolio.invest.domain.intelligence.PolicyStrength;
import com.portfolio.invest.domain.intelligence.SubscriptionRepository;
import com.portfolio.invest.domain.intelligence.SubscriptionStock;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 情报检索用例（MS-20 Task 12 最小版 + MS-21 Task 8 公告检索 + MS-22 Task 6 宏观简报）：
 * 组装 {@link PageQuery} 委托 {@link NewsRepository#search} /
 * {@link AnnouncementRepository#search} / {@link PolicyRepository#searchEvents}，把合并
 * 视图收敛为条目视图（LLM 工具与 P4 web 查询共用口径）。P4 扩四区块（板块/政策链/
 * 简报档/抽取统计）。
 *
 * <p>limit 夹紧 1..20（缺省 10）与 macroBrief 的 indicators 解析 / policyDays 夹紧
 * 1..90（缺省 30）单点收口在本服务——工具层与 web 层不各自防御；
 * page 固定 1（工具场景只取第一页）。
 */
@Service
public class IntelligenceQueryService {

    /** 单页条数缺省值。 */
    static final int DEFAULT_LIMIT = 10;

    /** 单页条数上限（工具口径；PageQuery 的 100 上限是 web 大分页口径）。 */
    static final int MAX_LIMIT = 20;

    /** 宏观简报缺省指标全集：五先行指标 + 国债收益率两点（F13 决策 #12 + T4 跨表合成）。 */
    static final List<String> MACRO_BRIEF_ALL_INDICATORS = List.of(
            "CPI", "PPI", "PMI", "LPR", "AFMI",
            MacroPoint.INDICATOR_TY1Y, MacroPoint.INDICATOR_TY10Y);

    /** 宏观简报每指标历史序列期数（近 5 期迷你趋势，LLM 上下文友好）。 */
    static final int MACRO_BRIEF_SERIES_LIMIT = 5;

    /** 政策回看天数缺省值。 */
    static final int DEFAULT_POLICY_DAYS = 30;

    /** 政策回看天数上限。 */
    static final int MAX_POLICY_DAYS = 90;

    /** 市场时区（政策窗口「今日」口径，与宏观采集/简报调度 zone 一致）。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** scope=subscription 空集话术（未设置订阅标的，引导先订阅再定向检索）。 */
    static final String SUBSCRIPTION_EMPTY_MESSAGE =
            "未设置订阅标的——可先到「情报订阅」添加关注股票后再检索（scope=subscription 仅检索订阅标的）";

    /** scope=holdings 空集话术（无建仓持仓阶段项目）。 */
    static final String HOLDINGS_EMPTY_MESSAGE =
            "当前无建仓持仓阶段项目——研究项目建仓后，持仓标的公告自动纳入检索范围（scope=holdings 仅检索持仓标的）";

    /** stock 参数不在 scope 标的集话术（区分于「该标的无公告」的误导性空结果）。 */
    static final String STOCK_OUT_OF_SUBSCRIPTION_MESSAGE =
            "该标的不在你的订阅标的范围内（scope=subscription 仅检索订阅标的）";

    static final String STOCK_OUT_OF_HOLDINGS_MESSAGE =
            "该标的不在你的持仓跟踪标的范围内（scope=holdings 仅检索持仓项目标的）";

    /**
     * scope 路径超采后仍全页未命中的残余护栏话术（全库最新页被 scope 外公告刷满——
     * 非「检索无结果」，加 stock 定向或收窄日期可解）。
     */
    static final String SUBSCRIPTION_PAGE_MISS_MESSAGE =
            "本页未命中你的订阅标的公告——可加 stock 参数定向检索或收窄日期范围";

    static final String HOLDINGS_PAGE_MISS_MESSAGE =
            "本页未命中你的持仓标的公告——可加 stock 参数定向检索或收窄日期范围";

    private final NewsRepository newsRepository;
    private final AnnouncementRepository announcementRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final IntelligenceSubscriptionHook subscriptionHook;
    private final MacroQueryService macroQueryService;
    private final PolicyRepository policyRepository;
    private final Clock clock;

    @Autowired
    public IntelligenceQueryService(NewsRepository newsRepository,
                                    AnnouncementRepository announcementRepository,
                                    SubscriptionRepository subscriptionRepository,
                                    IntelligenceSubscriptionHook subscriptionHook,
                                    MacroQueryService macroQueryService,
                                    PolicyRepository policyRepository) {
        this(newsRepository, announcementRepository, subscriptionRepository, subscriptionHook,
                macroQueryService, policyRepository, Clock.system(ZONE));
    }

    /** 测试构造器：注入时钟（macroBrief 的政策窗口「今日」与 generatedAt 可确定化）。 */
    IntelligenceQueryService(NewsRepository newsRepository,
                             AnnouncementRepository announcementRepository,
                             SubscriptionRepository subscriptionRepository,
                             IntelligenceSubscriptionHook subscriptionHook,
                             MacroQueryService macroQueryService,
                             PolicyRepository policyRepository,
                             Clock clock) {
        this.newsRepository = newsRepository;
        this.announcementRepository = announcementRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionHook = subscriptionHook;
        this.macroQueryService = macroQueryService;
        this.policyRepository = policyRepository;
        this.clock = clock;
    }

    /**
     * 检索结构化情报：条目（AI 摘要优先、缺席退化源站摘要）+ 命中总数。
     */
    public NewsSearchResult searchNews(NewsSearchFilter filter) {
        int limit = Math.min(
                Math.max(filter.limit() == null ? DEFAULT_LIMIT : filter.limit(), 1), MAX_LIMIT);
        PageQuery query = new PageQuery(1, limit,
                blankToNull(filter.q()), blankToNull(filter.stock()), blankToNull(filter.industry()),
                filter.from(), filter.to(), filter.minImportance());
        PageResult<NewsRecord> page = newsRepository.search(query);
        return new NewsSearchResult(page.items().stream().map(NewsItemView::of).toList(), page.total());
    }

    /**
     * 检索公告情报（MS-21 Task 8）：条目视图 + 命中总数 + scope 引导语。
     *
     * <p>scope 解析：<b>subscription</b>=当前用户订阅标的集（无行经
     * {@link IntelligenceSubscription#defaults} 物化，空集短路返回引导语不打仓库）；
     * <b>holdings</b>=持仓 hook 全用户列表按 userId 过滤（hook 尽力而为不抛，失败空集
     * 同走引导语）；<b>all</b>=不过滤。scope 命中后标的过滤在<b>本页内存</b>完成
     * （标的集小，不扩仓库 SQL IN——最小版口径）。
     *
     * <p><b>scope 路径本页超采</b>（fix round 1）：全库最新页可能被全市场 major 公告
     * 刷满，按 limit 取页会内存过滤成假空——故 pageSize 取 {@code min(100, max(20, limit×10))}
     * （all 路径照旧取夹紧 limit），命中过滤后裁回前 limit 条（published_at 倒序保持）；
     * total 为超采页命中且裁回后的条数而非全库精确 total。超采后仍全页未命中
     * （{@code items 空 ∧ page.total > 0}）给 scope 专属护栏话术（不用「检索无结果」
     * 误导）。P4 web 检索升级 SQL IN 后超采与护栏自然废止。stock 参数不在标的集时
     * 短路返回（省一次仓库调用）。
     */
    public AnnouncementSearchResult searchAnnouncements(Long userId, AnnouncementSearchFilter filter) {
        int limit = Math.min(
                Math.max(filter.limit() == null ? DEFAULT_LIMIT : filter.limit(), 1), MAX_LIMIT);
        AnnouncementScope scope = filter.scope() == null ? AnnouncementScope.ALL : filter.scope();
        Set<String> scopeStocks = scopeStocks(userId, scope);
        if (scopeStocks != null) {
            if (scopeStocks.isEmpty()) {
                return AnnouncementSearchResult.empty(scope == AnnouncementScope.SUBSCRIPTION
                        ? SUBSCRIPTION_EMPTY_MESSAGE : HOLDINGS_EMPTY_MESSAGE);
            }
            String stock = blankToNull(filter.stock());
            if (stock != null && !scopeStocks.contains(stock)) {
                return AnnouncementSearchResult.empty(scope == AnnouncementScope.SUBSCRIPTION
                        ? STOCK_OUT_OF_SUBSCRIPTION_MESSAGE : STOCK_OUT_OF_HOLDINGS_MESSAGE);
            }
        }
        // scope 路径超采（见方法 javadoc）；all 路径照夹紧 limit
        int pageSize = scopeStocks == null ? limit
                : Math.min(PageQuery.MAX_PAGE_SIZE, Math.max(MAX_LIMIT, limit * 10));
        PageQuery query = new PageQuery(1, pageSize, blankToNull(filter.q()), blankToNull(filter.stock()),
                null, filter.from(), filter.to(), null, filter.type(), null);
        PageResult<AnnouncementRecord> page = announcementRepository.search(query);
        List<AnnouncementItemView> items = page.items().stream()
                .filter(r -> scopeStocks == null || scopeStocks.contains(r.stockCode()))
                .limit(limit)
                .map(AnnouncementItemView::of)
                .toList();
        if (scopeStocks != null && items.isEmpty() && page.total() > 0) {
            // 残余护栏：全库有公告但超采页全被 scope 外刷满——非「检索无结果」
            return AnnouncementSearchResult.empty(scope == AnnouncementScope.SUBSCRIPTION
                    ? SUBSCRIPTION_PAGE_MISS_MESSAGE : HOLDINGS_PAGE_MISS_MESSAGE);
        }
        long total = scopeStocks == null ? page.total() : items.size();
        return new AnnouncementSearchResult(items, total, null);
    }

    /**
     * scope 标的集：all 返回 null（不过滤哨兵）；subscription 订阅聚合标的码；
     * holdings hook 全用户列表按 userId 过滤后的标的码。
     */
    private Set<String> scopeStocks(Long userId, AnnouncementScope scope) {
        return switch (scope) {
            case ALL -> null;
            case SUBSCRIPTION -> subscriptionRepository.findByUserId(userId)
                    .orElseGet(() -> IntelligenceSubscription.defaults(userId))
                    .stocks().stream().map(SubscriptionStock::stockCode)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            case HOLDINGS -> subscriptionHook.activePositionTargets().stream()
                    .filter(t -> userId.equals(t.userId()))
                    .map(IntelligenceTarget::stockCode)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        };
    }

    /** 空白串归一为 null（空白关键词不启用条件）。 */
    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /**
     * 宏观简报（MS-22 Task 6）：指标节（latest + 近 5 期序列）+ 政策节（近 policyDays 天
     * searchEvents，direction 不过滤）+ 缺失指标显式列出。
     *
     * <p>指标串解析：逗号分隔 trim/大写/去重保序，空/空白归
     * {@link #MACRO_BRIEF_ALL_INDICATORS}；policyDays 夹紧 1..90（缺省 30）。latest 一次
     * 取全指标后按请求序装配——请求指标在 latest 无命中（含未知指标码）进 missing
     * （F13 缺失不编造不省略），不查 series。TY 两点 series 恒空列表原样透传（T4 裁定：
     * 「仅最新点、无历史序列」的 note 字段翻译归工具层，服务层不当数据缺失处理）。
     * 政策窗口为 [今日-policyDays+1, 今日] 闭区间（Asia/Shanghai，与抽取游标窗口同口径）。
     */
    public MacroBriefResult macroBrief(MacroBriefFilter filter) {
        List<String> requested = requestedIndicators(filter.indicators());
        int policyDays = filter.policyDays() == null ? DEFAULT_POLICY_DAYS
                : Math.min(Math.max(filter.policyDays(), 1), MAX_POLICY_DAYS);
        LocalDate today = LocalDate.now(clock);

        Map<String, MacroPoint> latestByIndicator = new LinkedHashMap<>();
        for (MacroPoint point : macroQueryService.latest()) {
            latestByIndicator.put(point.indicator(), point);
        }
        List<MacroIndicatorView> indicators = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String indicator : requested) {
            MacroPoint point = latestByIndicator.get(indicator);
            if (point == null) {
                missing.add(indicator);
                continue;
            }
            List<SeriesPoint> series = macroQueryService
                    .series(indicator, MACRO_BRIEF_SERIES_LIMIT).stream()
                    .map(p -> new SeriesPoint(p.period(), p.value()))
                    .toList();
            indicators.add(new MacroIndicatorView(indicator, point.value(), point.yoy(),
                    point.period(), point.periodType(), series));
        }

        PageResult<PolicyEvent> page = policyRepository.searchEvents(new PageQuery(
                1, MAX_LIMIT, null, null, null, today.minusDays(policyDays - 1L), today,
                null, null, null, null));
        List<PolicyItemView> policies = page.items().stream().map(PolicyItemView::of).toList();
        return new MacroBriefResult(indicators, policies, page.total(), missing,
                Instant.now(clock));
    }

    /** 指标串解析：逗号分隔 trim + 大写归一 + 去重保序；空/空白归全七缺省。 */
    private static List<String> requestedIndicators(String indicators) {
        if (indicators == null || indicators.isBlank()) {
            return MACRO_BRIEF_ALL_INDICATORS;
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String part : indicators.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed.toUpperCase(Locale.ROOT));
            }
        }
        return normalized.isEmpty() ? MACRO_BRIEF_ALL_INDICATORS : List.copyOf(normalized);
    }

    /**
     * 检索结果信封：条目视图 + 命中总数（total 为满足全部条件的行数，可大于条目数）。
     */
    public record NewsSearchResult(List<NewsItemView> items, long total) {
        public NewsSearchResult {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /**
     * 情报条目视图（工具 JSON 与前端工具卡的契约形状）。
     *
     * @param title       标题
     * @param summary     摘要（AI 抽取摘要优先，无抽取行时退化源站摘要）
     * @param direction   方向（BULLISH 利好 / BEARISH 利空 / NEUTRAL 中性；未抽取为 null）
     * @param importance  重要度 0..100（未抽取为 null）
     * @param keyNumbers  关键数字（自描述字符串数组，如「Q3 净利润同比 +25.3%」；未抽取为空数组）
     * @param stockCodes  关联标的码
     * @param url         原文链接
     * @param publishedAt 发布时间
     */
    public record NewsItemView(
            String title,
            String summary,
            Direction direction,
            Integer importance,
            List<String> keyNumbers,
            List<String> stockCodes,
            String url,
            Instant publishedAt) {

        public NewsItemView {
            keyNumbers = keyNumbers == null ? List.of() : List.copyOf(keyNumbers);
            stockCodes = stockCodes == null ? List.of() : List.copyOf(stockCodes);
        }

        /** 合并视图 → 条目视图：summary 取 AI 摘要、缺席退化源站摘要；keyNumbers 直传（null 归一空数组）。 */
        static NewsItemView of(NewsRecord r) {
            return new NewsItemView(
                    r.title(),
                    r.extractSummary() != null ? r.extractSummary() : r.rawSummary(),
                    r.direction(), r.importance(), r.keyNumbers(), r.stockCodes(), r.url(), r.publishedAt());
        }
    }

    /**
     * 公告检索结果信封：条目视图 + 命中总数 + scope 引导语。scopeMessage 非 null 时
     * （订阅/持仓空集、标的不在范围）items 恒空——工具层以此区分「scope 未配置」
     * 与「检索无结果」两态话术。
     */
    public record AnnouncementSearchResult(
            List<AnnouncementItemView> items, long total, String scopeMessage) {

        public AnnouncementSearchResult {
            items = items == null ? List.of() : List.copyOf(items);
        }

        static AnnouncementSearchResult empty(String message) {
            return new AnnouncementSearchResult(List.of(), 0, message);
        }
    }

    /**
     * 公告条目视图（工具 JSON 与前端工具卡的契约形状）。
     *
     * @param title         标题
     * @param stockCode     标的代码
     * @param stockName     标的名称
     * @param annTypes      公告类型标签（枚举名数组，前端映射中文 label；null 归一空数组）
     * @param annTypeSource 源站栏目（annTypes 空时的类型兜底展示）
     * @param metrics       六字段业绩要点（未抽取成功为 null；未披露字段 null 且进 undisclosed）
     * @param pdfUrl        PDF 原文链接
     * @param publishedAt   发布时间
     */
    public record AnnouncementItemView(
            String title,
            String stockCode,
            String stockName,
            List<AnnouncementType> annTypes,
            String annTypeSource,
            AnnouncementMetrics metrics,
            String pdfUrl,
            Instant publishedAt) {

        public AnnouncementItemView {
            annTypes = annTypes == null ? List.of() : List.copyOf(annTypes);
        }

        /** 合并视图 → 条目视图：八字段直传（未抽取行 metrics 为 null、annTypes 归一空数组）。 */
        static AnnouncementItemView of(AnnouncementRecord r) {
            return new AnnouncementItemView(r.title(), r.stockCode(), r.stockName(),
                    r.annTypes(), r.annTypeSource(), r.metrics(), r.pdfUrl(), r.publishedAt());
        }
    }

    /**
     * 宏观简报结果信封（MS-22 Task 6）：指标节 + 政策节 + 缺失指标 + 生成时刻。
     *
     * @param indicators 指标条目（请求序，缺失指标不在此——在 missing）
     * @param policies   政策条目（近 policyDays 天，direction 不过滤；含 isPolicy=false 兜底行）
     * @param total      政策命中总数（可大于条目数）
     * @param missing    缺失指标码（库中无 latest 命中，含未知指标码；F13 显式列出）
     * @param generatedAt 生成时刻（数据截止期别在每指标的 period 字段）
     */
    public record MacroBriefResult(
            List<MacroIndicatorView> indicators,
            List<PolicyItemView> policies,
            long total,
            List<String> missing,
            Instant generatedAt) {

        public MacroBriefResult {
            indicators = indicators == null ? List.of() : List.copyOf(indicators);
            policies = policies == null ? List.of() : List.copyOf(policies);
            missing = missing == null ? List.of() : List.copyOf(missing);
        }
    }

    /**
     * 宏观指标条目视图（工具 JSON 与前端简报卡的契约形状）。
     *
     * @param indicator  指标码（CPI/PPI/PMI/LPR/AFMI/TY1Y/TY10Y）
     * @param value      最新值（可空——源未给出时缺席而非编造）
     * @param yoy        最新一期同比（可空）
     * @param period     数据截止期别（月度 YYYY-MM / 日度 YYYY-MM-DD——引用时注明）
     * @param periodType 期别类型（MONTH / DAY）
     * @param series     近 5 期序列（period 倒序——最新在前）；TY 两点恒空列表
     *                   （工具层翻译 note 字段，勿当数据缺失）
     */
    public record MacroIndicatorView(
            String indicator,
            BigDecimal value,
            BigDecimal yoy,
            String period,
            String periodType,
            List<SeriesPoint> series) {

        public MacroIndicatorView {
            series = series == null ? List.of() : List.copyOf(series);
        }
    }

    /** 序列单期投影（period 倒序）：期别 + 值。 */
    public record SeriesPoint(String period, BigDecimal value) {
    }

    /**
     * 政策条目视图（工具 JSON 与前端简报卡的契约形状）。
     *
     * @param title       政策标题
     * @param direction   政策取向（EASING 宽松 / TIGHTENING 收紧 / NEUTRAL 中性）
     * @param strength    政策力度（HIGH 强 / MEDIUM 中 / LOW 弱）
     * @param areas       影响领域
     * @param summary     一句话摘要（哨兵文案 = 非政策兜底行）
     * @param confidence  置信度（LOW = 低置信标注）
     * @param isPolicy    是否政策类发布（false = 非政策兜底行，调用方据此标注展示）
     * @param url         原文链接
     * @param publishedAt 发布时间
     */
    public record PolicyItemView(
            String title,
            PolicyDirection direction,
            PolicyStrength strength,
            List<String> areas,
            String summary,
            PolicyConfidence confidence,
            boolean isPolicy,
            String url,
            Instant publishedAt) {

        public PolicyItemView {
            areas = areas == null ? List.of() : List.copyOf(areas);
        }

        /** 合并视图 → 条目视图：九字段直传（isPolicy 派生标记保留，兜底行也返回）。 */
        static PolicyItemView of(PolicyEvent e) {
            return new PolicyItemView(e.title(), e.direction(), e.strength(),
                    e.affectedAreas(), e.summary(), e.confidence(), e.isPolicy(),
                    e.url(), e.publishedAt());
        }
    }
}
