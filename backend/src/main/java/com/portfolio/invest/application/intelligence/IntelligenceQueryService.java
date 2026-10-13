package com.portfolio.invest.application.intelligence;

import com.portfolio.invest.application.research.IntelligenceSubscriptionHook;
import com.portfolio.invest.domain.intelligence.AnnouncementMetrics;
import com.portfolio.invest.domain.intelligence.AnnouncementRecord;
import com.portfolio.invest.domain.intelligence.AnnouncementType;
import com.portfolio.invest.domain.intelligence.AnnouncementRepository;
import com.portfolio.invest.domain.intelligence.BriefRepository;
import com.portfolio.invest.domain.intelligence.Direction;
import com.portfolio.invest.domain.intelligence.NewsRecord;
import com.portfolio.invest.domain.intelligence.NewsRepository;
import com.portfolio.invest.domain.intelligence.PolicyConfidence;
import com.portfolio.invest.domain.intelligence.PolicyDirection;
import com.portfolio.invest.domain.intelligence.PolicyEvent;
import com.portfolio.invest.domain.intelligence.PolicyRepository;
import com.portfolio.invest.domain.intelligence.PolicyStrength;
import com.portfolio.invest.domain.intelligence.SubscriptionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 情报检索用例门面（2026-10-12 按读端点族拆分：原 637 行单类承载工具检索/宏观/分页/
 * 聚合 10+ 读端点，现纯搬移至
 * {@link IntelligenceSearchApplicationService}（工具检索 searchNews/searchAnnouncements +
 * scope 三态）、{@link IntelligenceMacroApplicationService}（macroBrief/macroOverview/calendar）、
 * {@link IntelligencePageApplicationService}（web 四区块分页 newsPage/announcementsPage/
 * policies/briefs/briefDetail）、{@link IntelligenceStockApplicationService}（stockIntel 标的聚合）；
 * 共享空白串归一见 {@link IntelligenceQuerySupport}）。
 *
 * <p>本门面保留全部对外契约：web/agent 注入点、方法签名与条目/结果视图 record 均未变
 * （web/agent/bdd/测试 import 不动），方法体为一行委托；行为与拆分前完全一致。
 *
 * <p>assemble {@link PageQuery} 委托 {@link NewsRepository#search} /
 * {@link AnnouncementRepository#search} / {@link PolicyRepository#searchEvents} /
 * {@link BriefRepository#search}，把合并视图收敛为条目视图（LLM 工具与 P4 web 查询
 * 共用口径）。P4 四区块（新闻流/公告流/政策库/简报归档）+ 标的聚合（F17）+ 宏观页
 * 聚合（macroOverview/calendar，D22）。
 *
 * <p>limit 夹紧 1..20（缺省 10）与 macroBrief 的 indicators 解析 / policyDays 夹紧
 * 1..90（缺省 30）单点收口在各子服务——工具层与 web 层不各自防御；page 固定 1（工具
 * 场景只取第一页）。P4 web 分页（newsPage 等四方法）page/pageSize 缺省 1/20、夹紧经
 * {@code PageQuery} compact constructor（page ≥ 1、1..100）。
 */
@Service
public class IntelligenceQueryService {

    /** 市场时区（政策窗口「今日」口径，与宏观采集/简报调度 zone 一致）。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final IntelligenceSearchApplicationService searchService;
    private final IntelligenceMacroApplicationService macroService;
    private final IntelligencePageApplicationService pageService;
    private final IntelligenceStockApplicationService stockService;

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
        this.searchService = new IntelligenceSearchApplicationService(newsRepository,
                announcementRepository, subscriptionRepository, subscriptionHook);
        this.macroService = new IntelligenceMacroApplicationService(macroQueryService, policyRepository, clock);
        this.pageService = new IntelligencePageApplicationService(newsRepository,
                announcementRepository, policyRepository, briefRepository);
        this.stockService = new IntelligenceStockApplicationService(newsRepository, announcementRepository);
    }

    /**
     * 检索结构化情报：条目（AI 摘要优先、缺席退化源站摘要）+ 命中总数。
     */
    public NewsSearchResult searchNews(NewsSearchFilter filter) {
        return searchService.searchNews(filter);
    }

    /**
     * 检索公告情报（MS-21 Task 8，P4 Task 1 scope 路径升级 SQL IN）：条目视图 +
     * 命中总数 + scope 引导语。scope 解析与残余护栏话术见
     * {@link IntelligenceSearchApplicationService#searchAnnouncements}。
     */
    public AnnouncementSearchResult searchAnnouncements(Long userId, AnnouncementSearchFilter filter) {
        return searchService.searchAnnouncements(userId, filter);
    }

    /**
     * 宏观简报（MS-22 Task 6）：指标节（latest + 近 5 期序列）+ 政策节（近 policyDays 天
     * searchEvents，direction 不过滤）+ 缺失指标显式列出。指标串解析与政策窗口口径见
     * {@link IntelligenceMacroApplicationService#macroBrief}。
     */
    public MacroBriefResult macroBrief(MacroBriefFilter filter) {
        return macroService.macroBrief(filter);
    }

    /**
     * 新闻流分页查询（/api/intelligence/news，D20 §4.3）：过滤器逐字段映射 PageQuery，
     * 条目映射与工具口径一致（{@link NewsItemView#of}）。
     */
    public IntelligenceViews.PageView<NewsItemView> newsPage(NewsFilter filter) {
        return pageService.newsPage(filter);
    }

    /**
     * 公告流分页查询（/api/intelligence/announcements）：全库检索 + 显式过滤
     * （q/stock/type/from/to/major），无 scope 概念（scope 为 Agent 工具定向入参）。
     */
    public IntelligenceViews.PageView<AnnouncementItemView> announcementsPage(
            AnnouncementFilter filter) {
        return pageService.announcementsPage(filter);
    }

    /**
     * 政策库分页查询（/api/intelligence/policies）：q 标题近似 / from·to 闭区间 /
     * direction 取向等值（仅 SUCCESS 行进检索面，兜底行带 isPolicy=false 标记）。
     */
    public IntelligenceViews.PageView<PolicyItemView> policies(PolicyFilter filter) {
        return pageService.policies(filter);
    }

    /**
     * 简报归档分页查询（/api/intelligence/briefs，决策 #23 检索维度）：stock 命中
     * top_stocks JSONB contains、q 走 content_md trgm、from/to 为 trade_date 闭区间；
     * 条目为档案行视图（不含 content_md 正文——详情走 {@link #briefDetail}）。
     */
    public IntelligenceViews.PageView<IntelligenceViews.BriefItemView> briefs(BriefFilter filter) {
        return pageService.briefs(filter);
    }

    /**
     * 简报档详情（/api/intelligence/briefs/{tradeDate}）：全字段含 content_md 正文；
     * 当日无档（含非交易日/未生成）抛 {@link IntelligenceException} NOT_FOUND——
     * 区别于「检索无结果」，调用方（web → 404）据此给明确提示。
     */
    public IntelligenceViews.BriefDetailView briefDetail(LocalDate tradeDate) {
        return pageService.briefDetail(tradeDate);
    }

    /**
     * 标的聚合（/api/intelligence/stocks/{code}，F17）：三路命中计数 + 新闻/公告各路
     * 最新 5 条；三路命中全零时 {@code empty=true}（前端空态引导）。空白标的码抛 INVALID_FILTER。
     */
    public IntelligenceViews.StockIntelView stockIntel(String stockCode) {
        return stockService.stockIntel(stockCode);
    }

    /**
     * 宏观总览（/api/intelligence/macro）：latest + 指标串解析归一（同 macroBrief），
     * 按请求序装配各指标 latest + 近 limit 期序列（缺省 5，夹紧 1..60）、缺失显式
     * 列出（F13）——与工具侧 macroBrief 的差异：无政策节（政策库区块独立）且序列
     * 期数可调。generatedAt 取服务时钟。
     */
    public IntelligenceViews.MacroOverviewView macroOverview(String indicators, Integer limit) {
        return macroService.macroOverview(indicators, limit);
    }

    /**
     * 宏观日历（/api/intelligence/macro/calendar，D22）：今日（Asia/Shanghai）起
     * days 天的预期发布日程（缺省 7 天——政策库区块日历卡「未来 7 天」口径；
     * MacroQueryService 夹紧 1..30）。预期非承诺（展示层带此口径）。
     */
    public List<IntelligenceViews.CalendarView> calendar(Integer days) {
        return macroService.calendar(days);
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
