package com.portfolio.invest.application.intelligence;

import com.portfolio.invest.application.research.IntelligenceSubscriptionHook;
import com.portfolio.invest.application.research.IntelligenceSubscriptionHook.IntelligenceTarget;
import com.portfolio.invest.domain.intelligence.AnnouncementMetrics;
import com.portfolio.invest.domain.intelligence.AnnouncementRecord;
import com.portfolio.invest.domain.intelligence.AnnouncementRepository;
import com.portfolio.invest.domain.intelligence.AnnouncementType;
import com.portfolio.invest.domain.intelligence.BriefRepository;
import com.portfolio.invest.domain.intelligence.Direction;
import com.portfolio.invest.domain.intelligence.IntelligenceErrorCode;
import com.portfolio.invest.domain.intelligence.IntelligenceException;
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
 * 情报检索用例（MS-20 Task 12 最小版 + MS-21 Task 8 公告检索 + MS-22 Task 6 宏观简报 +
 * P4 Task 1 四区块扩全）：组装 {@link PageQuery} 委托 {@link NewsRepository#search} /
 * {@link AnnouncementRepository#search} / {@link PolicyRepository#searchEvents} /
 * {@link BriefRepository#search}，把合并视图收敛为条目视图（LLM 工具与 P4 web 查询
 * 共用口径）。P4 四区块（新闻流/公告流/政策库/简报归档）+ 标的聚合（F17）+ 宏观页
 * 聚合（macroOverview/calendar，D22）。
 *
 * <p>limit 夹紧 1..20（缺省 10）与 macroBrief 的 indicators 解析 / policyDays 夹紧
 * 1..90（缺省 30）单点收口在本服务——工具层与 web 层不各自防御；
 * page 固定 1（工具场景只取第一页）。P4 web 分页（newsPage 等四方法）page/pageSize
 * 缺省 1/20、夹紧经 {@link PageQuery} compact constructor（page ≥ 1、1..100）。
 */
@Service
public class IntelligenceQueryService {

    /** 单页条数缺省值。 */
    static final int DEFAULT_LIMIT = 10;

    /** 单页条数上限（工具口径；PageQuery 的 100 上限是 web 大分页口径）。 */
    static final int MAX_LIMIT = 20;

    /** web 分页缺省页码（D20：page 默认 1）。 */
    static final int DEFAULT_PAGE = 1;

    /** web 分页缺省页大小（D20：pageSize 默认 20）。 */
    static final int DEFAULT_PAGE_SIZE = 20;

    /** 标的聚合各路最新条数（F17 三路聚合的每路条目数）。 */
    static final int STOCK_INTEL_ITEMS = 5;

    /** 标的聚合政策路说明（政策事件无标的维度——引导到政策库检索）。 */
    static final String POLICY_NO_STOCK_NOTE =
            "政策事件不按标的归集——请到「政策库」按方向/日期/关键词检索";

    /** 宏观日历缺省天数（D22：政策库区块日历卡「未来 7 天预期发布」）。 */
    static final int DEFAULT_CALENDAR_DAYS = 7;

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
     * scope 路径残余护栏话术（P4 SQL IN 升级后为纯防御：items 空 ∧ total>0 的仓库违约
     * 场景才触发——非「检索无结果」，加 stock 定向或收窄日期可解）。
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
    private final BriefRepository briefRepository;
    private final Clock clock;

    @Autowired
    public IntelligenceQueryService(NewsRepository newsRepository,
                                    AnnouncementRepository announcementRepository,
                                    SubscriptionRepository subscriptionRepository,
                                    IntelligenceSubscriptionHook subscriptionHook,
                                    MacroQueryService macroQueryService,
                                    PolicyRepository policyRepository,
                                    BriefRepository briefRepository) {
        this(newsRepository, announcementRepository, subscriptionRepository, subscriptionHook,
                macroQueryService, policyRepository, briefRepository, Clock.system(ZONE));
    }

    /** 测试构造器：注入时钟（macroBrief 的政策窗口「今日」与 generatedAt 可确定化）。 */
    IntelligenceQueryService(NewsRepository newsRepository,
                             AnnouncementRepository announcementRepository,
                             SubscriptionRepository subscriptionRepository,
                             IntelligenceSubscriptionHook subscriptionHook,
                             MacroQueryService macroQueryService,
                             PolicyRepository policyRepository,
                             BriefRepository briefRepository,
                             Clock clock) {
        this.newsRepository = newsRepository;
        this.announcementRepository = announcementRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionHook = subscriptionHook;
        this.macroQueryService = macroQueryService;
        this.policyRepository = policyRepository;
        this.briefRepository = briefRepository;
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
     * 检索公告情报（MS-21 Task 8，P4 Task 1 scope 路径升级 SQL IN）：条目视图 +
     * 命中总数 + scope 引导语。
     *
     * <p>scope 解析：<b>subscription</b>=当前用户订阅标的集（无行经
     * {@link IntelligenceSubscription#defaults} 物化，空集短路返回引导语不打仓库）；
     * <b>holdings</b>=持仓 hook 全用户列表按 userId 过滤（hook 尽力而为不抛，失败空集
     * 同走引导语）；<b>all</b>=不过滤。<b>已升级 SQL IN</b>（P4 Task 1，废止最小版的
     * 「本页超采 + 内存过滤」口径）：scope 标的集经 {@link PageQuery#stockCodes()} 以
     * SQL IN 下推仓库，pageSize 取夹紧 limit（不超采），total 为全库精确命中数；
     * stock 参数不在标的集时短路返回（省一次仓库调用）。
     *
     * <p>护栏话术保留为<b>残余防御</b>：SQL IN 契约下 items 空即真无命中（total 同为 0），
     * {@code items 空 ∧ total > 0} 理论上不再出现——万一仓库违约仍给 scope 专属话术
     * （不用「检索无结果」误导），保留无害。
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
        // scope 标的集 SQL IN 下推（all 路径 stockCodes=null 不过滤）；pageSize=夹紧 limit 不超采
        PageQuery query = new PageQuery(1, limit, blankToNull(filter.q()), blankToNull(filter.stock()),
                null, filter.from(), filter.to(), null, filter.type(), null, null,
                scopeStocks == null ? null : List.copyOf(scopeStocks));
        PageResult<AnnouncementRecord> page = announcementRepository.search(query);
        List<AnnouncementItemView> items = page.items().stream()
                .map(AnnouncementItemView::of)
                .toList();
        if (scopeStocks != null && items.isEmpty() && page.total() > 0) {
            // 残余防御（SQL IN 契约下不应发生）：全库有公告但本页未命中 scope——非「检索无结果」
            return AnnouncementSearchResult.empty(scope == AnnouncementScope.SUBSCRIPTION
                    ? SUBSCRIPTION_PAGE_MISS_MESSAGE : HOLDINGS_PAGE_MISS_MESSAGE);
        }
        return new AnnouncementSearchResult(items, page.total(), null);
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

        List<String> missing = new ArrayList<>();
        List<MacroIndicatorView> indicators =
                assembleIndicators(requested, MACRO_BRIEF_SERIES_LIMIT, missing);

        PageResult<PolicyEvent> page = policyRepository.searchEvents(new PageQuery(
                1, MAX_LIMIT, null, null, null, today.minusDays(policyDays - 1L), today,
                null, null, null, null));
        List<PolicyItemView> policies = page.items().stream().map(PolicyItemView::of).toList();
        return new MacroBriefResult(indicators, policies, page.total(), missing,
                Instant.now(clock));
    }

    /**
     * 指标节装配（macroBrief 与 macroOverview 共用）：latest 一次取全指标后按请求序
     * 装配，请求指标在 latest 无命中（含未知指标码）进 missing、不查 series；命中指标
     * 各取 seriesLimit 期序列（period 倒序）。TY 两点 series 恒空列表原样透传
     * （T4 裁定：note 字段翻译归展示层，不当数据缺失处理）。
     */
    private List<MacroIndicatorView> assembleIndicators(List<String> requested, int seriesLimit,
                                                        List<String> missing) {
        Map<String, MacroPoint> latestByIndicator = new LinkedHashMap<>();
        for (MacroPoint point : macroQueryService.latest()) {
            latestByIndicator.put(point.indicator(), point);
        }
        List<MacroIndicatorView> indicators = new ArrayList<>();
        for (String indicator : requested) {
            MacroPoint point = latestByIndicator.get(indicator);
            if (point == null) {
                missing.add(indicator);
                continue;
            }
            List<SeriesPoint> series = macroQueryService
                    .series(indicator, seriesLimit).stream()
                    .map(p -> new SeriesPoint(p.period(), p.value()))
                    .toList();
            indicators.add(new MacroIndicatorView(indicator, point.value(), point.yoy(),
                    point.period(), point.periodType(), series));
        }
        return indicators;
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

    // ===== P4 Task 1：四区块分页查询（web 端点口径，工具方法 searchNews 等不动）=====

    /**
     * 新闻流分页查询（/api/intelligence/news，D20 §4.3）：过滤器逐字段映射 PageQuery
     * （空白 q/stock/industry 归 null 不启用），page/pageSize 缺省 1/20、夹紧经
     * {@link PageQuery}（page ≥ 1、pageSize 1..100）；条目映射与工具口径一致
     * （{@link NewsItemView#of}——AI 摘要优先退化源站摘要）。
     */
    public IntelligenceViews.PageView<NewsItemView> newsPage(NewsFilter filter) {
        PageQuery query = new PageQuery(pageOr(filter.page()), pageSizeOr(filter.pageSize()),
                blankToNull(filter.q()), blankToNull(filter.stock()), blankToNull(filter.industry()),
                filter.from(), filter.to(), filter.minImportance(), null, null, null, null);
        return IntelligenceViews.PageView.of(newsRepository.search(query))
                .map(NewsItemView::of);
    }

    /**
     * 公告流分页查询（/api/intelligence/announcements）：全库检索 + 显式过滤
     * （q/stock/type/from/to/major），无 scope 概念（scope 为 Agent 工具定向入参）；
     * 分页缺省与夹紧同 {@link #newsPage}。
     */
    public IntelligenceViews.PageView<AnnouncementItemView> announcementsPage(
            AnnouncementFilter filter) {
        PageQuery query = new PageQuery(pageOr(filter.page()), pageSizeOr(filter.pageSize()),
                blankToNull(filter.q()), blankToNull(filter.stock()), null,
                filter.from(), filter.to(), null, filter.type(), filter.major(), null, null);
        return IntelligenceViews.PageView.of(announcementRepository.search(query))
                .map(AnnouncementItemView::of);
    }

    /**
     * 政策库分页查询（/api/intelligence/policies）：q 标题近似 / from·to 闭区间 /
     * direction 取向等值（仅 SUCCESS 行进检索面，兜底行带 isPolicy=false 标记）；
     * 分页缺省与夹紧同 {@link #newsPage}。
     */
    public IntelligenceViews.PageView<PolicyItemView> policies(PolicyFilter filter) {
        PageQuery query = new PageQuery(pageOr(filter.page()), pageSizeOr(filter.pageSize()),
                blankToNull(filter.q()), null, null, filter.from(), filter.to(),
                null, null, null, filter.direction(), null);
        return IntelligenceViews.PageView.of(policyRepository.searchEvents(query))
                .map(PolicyItemView::of);
    }

    /**
     * 简报归档分页查询（/api/intelligence/briefs，决策 #23 检索维度）：stock 命中
     * top_stocks JSONB contains、q 走 content_md trgm、from/to 为 trade_date 闭区间；
     * 条目为档案行视图（不含 content_md 正文——详情走 {@link #briefDetail}）。
     */
    public IntelligenceViews.PageView<IntelligenceViews.BriefItemView> briefs(BriefFilter filter) {
        PageQuery query = new PageQuery(pageOr(filter.page()), pageSizeOr(filter.pageSize()),
                blankToNull(filter.q()), blankToNull(filter.stock()), null,
                filter.from(), filter.to(), null);
        return IntelligenceViews.PageView.of(briefRepository.search(query))
                .map(IntelligenceViews.BriefItemView::of);
    }

    /**
     * 简报档详情（/api/intelligence/briefs/{tradeDate}）：全字段含 content_md 正文；
     * 当日无档（含非交易日/未生成）抛 {@link IntelligenceException} NOT_FOUND——
     * 区别于「检索无结果」，调用方（web → 404）据此给明确提示。
     */
    public IntelligenceViews.BriefDetailView briefDetail(LocalDate tradeDate) {
        return briefRepository.findByDate(tradeDate)
                .map(IntelligenceViews.BriefDetailView::of)
                .orElseThrow(() -> new IntelligenceException(IntelligenceErrorCode.NOT_FOUND,
                        "当日简报不存在：" + tradeDate));
    }

    // ===== P4 Task 1：标的聚合（F17）与宏观页聚合（D22）=====

    /**
     * 标的聚合（/api/intelligence/stocks/{code}，F17）：三路命中计数 + 新闻/公告各路
     * 最新 {@link #STOCK_INTEL_ITEMS} 条。新闻路走 stock_codes JSONB contains、公告路
     * 走 stock_code 直列等值、政策路无标的维度（计数恒 0 + 引导说明）；三路命中全零
     * 时 {@code empty=true}（前端空态引导）。空白标的码抛 INVALID_FILTER。
     */
    public IntelligenceViews.StockIntelView stockIntel(String stockCode) {
        String code = blankToNull(stockCode);
        if (code == null) {
            throw new IntelligenceException(IntelligenceErrorCode.INVALID_FILTER,
                    "stockCode 不能为空");
        }
        PageResult<NewsRecord> news = newsRepository.search(new PageQuery(
                1, STOCK_INTEL_ITEMS, null, code, null, null, null, null));
        PageResult<AnnouncementRecord> announcements = announcementRepository.search(new PageQuery(
                1, STOCK_INTEL_ITEMS, null, code, null, null, null, null));
        boolean empty = news.total() == 0 && announcements.total() == 0;
        return new IntelligenceViews.StockIntelView(
                code,
                news.total(),
                news.items().stream().map(NewsItemView::of).toList(),
                announcements.total(),
                announcements.items().stream().map(AnnouncementItemView::of).toList(),
                0,
                POLICY_NO_STOCK_NOTE,
                empty);
    }

    /**
     * 宏观总览（/api/intelligence/macro）：latest + 指标串解析归一（同 macroBrief），
     * 按请求序装配各指标 latest + 近 limit 期序列（缺省 5，夹紧 1..60）、缺失显式
     * 列出（F13）——与工具侧 macroBrief 的差异：无政策节（政策库区块独立）且序列
     * 期数可调。generatedAt 取服务时钟。
     */
    public IntelligenceViews.MacroOverviewView macroOverview(String indicators, Integer limit) {
        int seriesLimit = limit == null ? MACRO_BRIEF_SERIES_LIMIT
                : Math.min(Math.max(limit, 1), MacroQueryService.MAX_SERIES_LIMIT);
        List<String> missing = new ArrayList<>();
        List<MacroIndicatorView> assembled =
                assembleIndicators(requestedIndicators(indicators), seriesLimit, missing);
        return new IntelligenceViews.MacroOverviewView(assembled, missing, Instant.now(clock));
    }

    /**
     * 宏观日历（/api/intelligence/macro/calendar，D22）：今日（Asia/Shanghai）起
     * days 天的预期发布日程（缺省 {@link #DEFAULT_CALENDAR_DAYS} 天——政策库区块
     * 日历卡「未来 7 天」口径；MacroQueryService 夹紧 1..30）。预期非承诺
     * （展示层带此口径）。
     */
    public List<IntelligenceViews.CalendarView> calendar(Integer days) {
        return macroQueryService
                .calendarUpcoming(days == null ? DEFAULT_CALENDAR_DAYS : days)
                .stream()
                .map(IntelligenceViews.CalendarView::of)
                .toList();
    }

    /** web 分页页码缺省解析（null → 1；越界夹紧归 PageQuery）。 */
    private static int pageOr(Integer page) {
        return page == null ? DEFAULT_PAGE : page;
    }

    /** web 分页页大小缺省解析（null → 20；越界夹紧归 PageQuery）。 */
    private static int pageSizeOr(Integer pageSize) {
        return pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
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
