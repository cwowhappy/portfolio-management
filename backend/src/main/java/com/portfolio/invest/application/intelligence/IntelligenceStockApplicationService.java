package com.portfolio.invest.application.intelligence;

import static com.portfolio.invest.application.intelligence.IntelligenceQuerySupport.blankToNull;

import com.portfolio.invest.application.intelligence.IntelligenceQueryService.AnnouncementItemView;
import com.portfolio.invest.application.intelligence.IntelligenceQueryService.NewsItemView;
import com.portfolio.invest.domain.intelligence.AnnouncementRecord;
import com.portfolio.invest.domain.intelligence.AnnouncementRepository;
import com.portfolio.invest.domain.intelligence.IntelligenceErrorCode;
import com.portfolio.invest.domain.intelligence.IntelligenceException;
import com.portfolio.invest.domain.intelligence.NewsRecord;
import com.portfolio.invest.domain.intelligence.NewsRepository;
import com.portfolio.invest.domain.intelligence.PageQuery;
import com.portfolio.invest.domain.intelligence.PageResult;

/**
 * 标的聚合读端点族（/api/intelligence/stocks/{code}，F17 三路聚合）。2026-10-12 按读
 * 端点族拆分自原 {@link IntelligenceQueryService}（纯搬移，行为不变）；本类为门面手工
 * 装配、非 Spring bean。
 */
public class IntelligenceStockApplicationService {

    /** 标的聚合各路最新条数（F17 三路聚合的每路条目数）。 */
    static final int STOCK_INTEL_ITEMS = 5;

    /** 标的聚合政策路说明（政策事件无标的维度——引导到政策库检索）。 */
    static final String POLICY_NO_STOCK_NOTE =
            "政策事件不按标的归集——请到「政策库」按方向/日期/关键词检索";

    private final NewsRepository newsRepository;
    private final AnnouncementRepository announcementRepository;

    public IntelligenceStockApplicationService(NewsRepository newsRepository,
                                               AnnouncementRepository announcementRepository) {
        this.newsRepository = newsRepository;
        this.announcementRepository = announcementRepository;
    }

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
}
