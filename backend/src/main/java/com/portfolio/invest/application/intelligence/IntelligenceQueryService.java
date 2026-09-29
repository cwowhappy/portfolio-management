package com.portfolio.invest.application.intelligence;

import com.portfolio.invest.domain.intelligence.Direction;
import com.portfolio.invest.domain.intelligence.NewsRecord;
import com.portfolio.invest.domain.intelligence.NewsRepository;
import com.portfolio.invest.domain.intelligence.PageQuery;
import com.portfolio.invest.domain.intelligence.PageResult;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 情报检索用例（MS-20 Task 12 最小版）：组装 {@link PageQuery} 委托
 * {@link NewsRepository#search}，把 {@link NewsRecord} 合并视图收敛为条目视图
 * （LLM 工具与 P4 web 查询共用口径）。P4 扩四区块（板块/政策链/简报档/抽取统计）。
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

    private final NewsRepository newsRepository;

    public IntelligenceQueryService(NewsRepository newsRepository) {
        this.newsRepository = newsRepository;
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
}
