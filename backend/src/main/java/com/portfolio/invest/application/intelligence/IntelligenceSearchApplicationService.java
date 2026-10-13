package com.portfolio.invest.application.intelligence;

import static com.portfolio.invest.application.intelligence.IntelligenceQuerySupport.blankToNull;

import com.portfolio.invest.application.intelligence.IntelligenceQueryService.AnnouncementItemView;
import com.portfolio.invest.application.intelligence.IntelligenceQueryService.AnnouncementSearchResult;
import com.portfolio.invest.application.intelligence.IntelligenceQueryService.NewsItemView;
import com.portfolio.invest.application.intelligence.IntelligenceQueryService.NewsSearchResult;
import com.portfolio.invest.application.research.IntelligenceSubscriptionHook;
import com.portfolio.invest.application.research.IntelligenceSubscriptionHook.IntelligenceTarget;
import com.portfolio.invest.domain.intelligence.AnnouncementRecord;
import com.portfolio.invest.domain.intelligence.AnnouncementRepository;
import com.portfolio.invest.domain.intelligence.IntelligenceSubscription;
import com.portfolio.invest.domain.intelligence.NewsRecord;
import com.portfolio.invest.domain.intelligence.NewsRepository;
import com.portfolio.invest.domain.intelligence.PageQuery;
import com.portfolio.invest.domain.intelligence.PageResult;
import com.portfolio.invest.domain.intelligence.SubscriptionRepository;
import com.portfolio.invest.domain.intelligence.SubscriptionStock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 情报工具检索用例族（LLM 工具面：search_news / search_announcements）。2026-10-12 按读端点族
 * 拆分自原 {@link IntelligenceQueryService}（纯搬移，行为不变）；本类为门面手工装配、非 Spring bean。
 *
 * <p>limit 夹紧 1..20（缺省 10）单点收口在本服务——工具层不各自防御；page 固定 1
 * （工具场景只取第一页）。公告 scope 三态（all/subscription/holdings）与残余护栏话术同原口径。
 */
public class IntelligenceSearchApplicationService {

    /** 单页条数缺省值。 */
    static final int DEFAULT_LIMIT = 10;

    /** 单页条数上限（工具口径；PageQuery 的 100 上限是 web 大分页口径）。 */
    static final int MAX_LIMIT = 20;

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

    public IntelligenceSearchApplicationService(NewsRepository newsRepository,
                                                AnnouncementRepository announcementRepository,
                                                SubscriptionRepository subscriptionRepository,
                                                IntelligenceSubscriptionHook subscriptionHook) {
        this.newsRepository = newsRepository;
        this.announcementRepository = announcementRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionHook = subscriptionHook;
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
}
