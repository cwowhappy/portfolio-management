package com.portfolio.invest.application.intelligence;

import static com.portfolio.invest.application.intelligence.IntelligenceQuerySupport.blankToNull;

import com.portfolio.invest.application.intelligence.IntelligenceQueryService.AnnouncementItemView;
import com.portfolio.invest.application.intelligence.IntelligenceQueryService.NewsItemView;
import com.portfolio.invest.application.intelligence.IntelligenceQueryService.PolicyItemView;
import com.portfolio.invest.domain.intelligence.AnnouncementRepository;
import com.portfolio.invest.domain.intelligence.BriefRepository;
import com.portfolio.invest.domain.intelligence.IntelligenceErrorCode;
import com.portfolio.invest.domain.intelligence.IntelligenceException;
import com.portfolio.invest.domain.intelligence.NewsRepository;
import com.portfolio.invest.domain.intelligence.PageQuery;
import com.portfolio.invest.domain.intelligence.PolicyRepository;
import java.time.LocalDate;

/**
 * 情报工作台四区块分页读端点族（P4 Task 1：newsPage/announcementsPage/policies/briefs
 * + briefDetail）。2026-10-12 按读端点族拆分自原 {@link IntelligenceQueryService}
 * （纯搬移，行为不变）；本类为门面手工装配、非 Spring bean。page/pageSize 缺省 1/20、
 * 夹紧经 {@link PageQuery} compact constructor（page ≥ 1、1..100）。
 */
public class IntelligencePageApplicationService {

    /** web 分页缺省页码（D20：page 默认 1）。 */
    static final int DEFAULT_PAGE = 1;

    /** web 分页缺省页大小（D20：pageSize 默认 20）。 */
    static final int DEFAULT_PAGE_SIZE = 20;

    private final NewsRepository newsRepository;
    private final AnnouncementRepository announcementRepository;
    private final PolicyRepository policyRepository;
    private final BriefRepository briefRepository;

    public IntelligencePageApplicationService(NewsRepository newsRepository,
                                              AnnouncementRepository announcementRepository,
                                              PolicyRepository policyRepository,
                                              BriefRepository briefRepository) {
        this.newsRepository = newsRepository;
        this.announcementRepository = announcementRepository;
        this.policyRepository = policyRepository;
        this.briefRepository = briefRepository;
    }

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

    /** web 分页页码缺省解析（null → 1；越界夹紧归 PageQuery）。 */
    private static int pageOr(Integer page) {
        return page == null ? DEFAULT_PAGE : page;
    }

    /** web 分页页大小缺省解析（null → 20；越界夹紧归 PageQuery）。 */
    private static int pageSizeOr(Integer pageSize) {
        return pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
    }
}
