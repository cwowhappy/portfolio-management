package com.portfolio.invest.application.intelligence;

import com.portfolio.invest.application.research.IntelligenceSubscriptionHook;
import com.portfolio.invest.application.research.IntelligenceSubscriptionHook.IntelligenceTarget;
import com.portfolio.invest.domain.intelligence.AnnouncementMetrics;
import com.portfolio.invest.domain.intelligence.AnnouncementRecord;
import com.portfolio.invest.domain.intelligence.AnnouncementRepository;
import com.portfolio.invest.domain.intelligence.AnnouncementType;
import com.portfolio.invest.domain.intelligence.Direction;
import com.portfolio.invest.domain.intelligence.IntelligenceSubscription;
import com.portfolio.invest.domain.intelligence.NewsRecord;
import com.portfolio.invest.domain.intelligence.NewsRepository;
import com.portfolio.invest.domain.intelligence.PageQuery;
import com.portfolio.invest.domain.intelligence.PageResult;
import com.portfolio.invest.domain.intelligence.SubscriptionRepository;
import com.portfolio.invest.domain.intelligence.SubscriptionStock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 情报检索用例（MS-20 Task 12 最小版 + MS-21 Task 8 公告检索）：组装 {@link PageQuery}
 * 委托 {@link NewsRepository#search} / {@link AnnouncementRepository#search}，把合并视图
 * 收敛为条目视图（LLM 工具与 P4 web 查询共用口径）。P4 扩四区块（板块/政策链/简报档/抽取统计）。
 *
 * <p>limit 夹紧 1..20（缺省 10）单点收口在本服务——工具层与 web 层不各自防御；
 * page 固定 1（工具场景只取第一页）。
 */
@Service
public class IntelligenceQueryService {

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

    private final NewsRepository newsRepository;
    private final AnnouncementRepository announcementRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final IntelligenceSubscriptionHook subscriptionHook;

    public IntelligenceQueryService(NewsRepository newsRepository,
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
     * 检索公告情报（MS-21 Task 8）：条目视图 + 命中总数 + scope 引导语。
     *
     * <p>scope 解析：<b>subscription</b>=当前用户订阅标的集（无行经
     * {@link IntelligenceSubscription#defaults} 物化，空集短路返回引导语不打仓库）；
     * <b>holdings</b>=持仓 hook 全用户列表按 userId 过滤（hook 尽力而为不抛，失败空集
     * 同走引导语）；<b>all</b>=不过滤。scope 命中后标的过滤在<b>本页内存</b>完成
     * （标的集小，不扩仓库 SQL IN——最小版口径）：total 为该页 scope 内命中数而非
     * 全库精确 total；stock 参数不在标的集时短路返回（省一次仓库调用）。
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
        PageQuery query = new PageQuery(1, limit, blankToNull(filter.q()), blankToNull(filter.stock()),
                null, filter.from(), filter.to(), null, filter.type(), null);
        PageResult<AnnouncementRecord> page = announcementRepository.search(query);
        List<AnnouncementItemView> items = page.items().stream()
                .filter(r -> scopeStocks == null || scopeStocks.contains(r.stockCode()))
                .map(AnnouncementItemView::of)
                .toList();
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
}
