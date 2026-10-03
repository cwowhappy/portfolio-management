package com.portfolio.invest.application.intelligence;

import com.portfolio.invest.domain.intelligence.BriefStatus;
import com.portfolio.invest.domain.intelligence.DailyBrief;
import com.portfolio.invest.domain.intelligence.MacroCalendarEntry;
import com.portfolio.invest.domain.intelligence.PageResult;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;

/**
 * P4 工作台 web 查询视图集（D20 §4.3）：{@link PageView} 分页信封 + 简报档/标的聚合/
 * 宏观页 View records。既有条目视图（{@link IntelligenceQueryService.NewsItemView} 等）
 * 为 Agent 工具契约（已稳定）仍留在服务内，本类新视图按需引用。
 */
public final class IntelligenceViews {
    private IntelligenceViews() {}

    /**
     * 分页信封（D20）：条目 + 命中总数 + 回显页码/页大小——由 domain {@link PageResult}
     * 映射（total 为满足全部过滤条件的行数，可大于条目数）。
     */
    public record PageView<T>(List<T> items, long total, int page, int pageSize) {

        public PageView {
            items = items == null ? List.of() : List.copyOf(items);
        }

        /** PageResult（仓库出参）→ PageView（API 信封）。 */
        public static <T> PageView<T> of(PageResult<T> page) {
            return new PageView<>(page.items(), page.total(), page.page(), page.pageSize());
        }

        /** 条目逐个映射（域记录 → 条目视图），分页元数据原样保留。 */
        public <R> PageView<R> map(Function<? super T, ? extends R> mapper) {
            List<R> mapped = new java.util.ArrayList<>(items.size());
            for (T item : items) {
                mapped.add(mapper.apply(item));
            }
            return new PageView<>(mapped, total, page, pageSize);
        }
    }

    /**
     * 简报档列表条目视图（/api/intelligence/briefs）：不含 content_md 正文（归档列表
     * 只展示元信息，点开走 {@link BriefDetailView} 详情）。
     *
     * @param tradeDate   交易日
     * @param status      GENERATED / EMPTY_SIMPLE / FAILED
     * @param topStocks   涉及标的频次快照（频次降序，至多 20）
     * @param model       生成时模型标识
     * @param generatedAt 落档时间
     */
    public record BriefItemView(
            LocalDate tradeDate,
            BriefStatus status,
            List<String> topStocks,
            String model,
            Instant generatedAt) {

        public BriefItemView {
            topStocks = topStocks == null ? List.of() : List.copyOf(topStocks);
        }

        /** 归档域记录 → 列表条目视图（五字段直传，正文不进列表）。 */
        public static BriefItemView of(DailyBrief brief) {
            return new BriefItemView(brief.tradeDate(), brief.status(), brief.topStocks(),
                    brief.model(), brief.generatedAt());
        }
    }

    /**
     * 简报档详情视图（/api/intelligence/briefs/{tradeDate}）：全字段含 content_md 正文
     * 与失败原因；缺档由服务抛 {@code IntelligenceException NOT_FOUND}（HTTP 404）。
     *
     * @param tradeDate   交易日
     * @param contentMd   5+1 节 markdown（或空简版/失败版文本）
     * @param topStocks   涉及标的频次快照
     * @param status      GENERATED / EMPTY_SIMPLE / FAILED
     * @param failReason  失败原因（仅 FAILED，其余 null）
     * @param model       生成时模型标识
     * @param generatedAt 落档时间
     */
    public record BriefDetailView(
            LocalDate tradeDate,
            String contentMd,
            List<String> topStocks,
            BriefStatus status,
            String failReason,
            String model,
            Instant generatedAt) {

        public BriefDetailView {
            topStocks = topStocks == null ? List.of() : List.copyOf(topStocks);
        }

        /** 归档域记录 → 详情视图（七字段直传）。 */
        public static BriefDetailView of(DailyBrief brief) {
            return new BriefDetailView(brief.tradeDate(), brief.contentMd(), brief.topStocks(),
                    brief.status(), brief.failReason(), brief.model(), brief.generatedAt());
        }
    }

    /**
     * 标的聚合视图（/api/intelligence/stocks/{code}，F17）：三路命中计数 + 新闻/公告
     * 各路最新 {@link IntelligenceQueryService#STOCK_INTEL_ITEMS} 条。政策事件无标的
     * 维度——policyTotal 恒 0、policyNote 给出引导说明；三路命中全零时 empty=true
     * （前端空态引导，区别于「检索无结果」）。
     *
     * @param stockCode        标的代码
     * @param newsTotal        新闻命中总数（stock_codes JSONB contains）
     * @param news             新闻最新条目
     * @param announcementTotal 公告命中总数（stock_code 直列等值）
     * @param announcements    公告最新条目
     * @param policyTotal      政策命中总数（恒 0——政策事件不按标的归集）
     * @param policyNote       政策路说明（引导到政策库按方向/领域检索）
     * @param empty            三路命中全零 → 空态引导 flag
     */
    public record StockIntelView(
            String stockCode,
            long newsTotal,
            List<IntelligenceQueryService.NewsItemView> news,
            long announcementTotal,
            List<IntelligenceQueryService.AnnouncementItemView> announcements,
            long policyTotal,
            String policyNote,
            boolean empty) {

        public StockIntelView {
            news = news == null ? List.of() : List.copyOf(news);
            announcements = announcements == null ? List.of() : List.copyOf(announcements);
        }
    }

    /**
     * 宏观日历条目视图（/api/intelligence/macro/calendar，D22）：指标的**预期**发布
     * 日程——预期非承诺（实际发布可能提前/推迟/缺席，展示层须带此口径）。
     *
     * @param indicator    指标码（CPI/PPI/PMI/LPR/AFMI）
     * @param expectedDate 预期发布日
     * @param frequency    发布频率（MONTH）
     * @param sourceSite   来源站点名
     * @param updatedAt    日历行最近维护时间（D22 日历卡展示）
     */
    public record CalendarView(
            String indicator,
            LocalDate expectedDate,
            String frequency,
            String sourceSite,
            Instant updatedAt) {

        /** 日历域记录 → 条目视图（五字段直传）。 */
        public static CalendarView of(MacroCalendarEntry entry) {
            return new CalendarView(entry.indicator(), entry.expectedDate(), entry.frequency(),
                    entry.sourceSite(), entry.updatedAt());
        }
    }

    /**
     * 宏观总览视图（/api/intelligence/macro）：按请求序装配的指标节（latest + 近 N 期
     * 序列）+ 缺失指标显式列出（F13 不编造不省略）——与工具侧 macroBrief 的差异：
     * 无政策节（政策库区块独立）且序列期数可调。
     *
     * @param indicators  指标条目（请求序，缺失指标不在——在 missing）
     * @param missing     缺失指标码（库中无 latest 命中，含未知指标码）
     * @param generatedAt 生成时刻
     */
    public record MacroOverviewView(
            List<IntelligenceQueryService.MacroIndicatorView> indicators,
            List<String> missing,
            Instant generatedAt) {

        public MacroOverviewView {
            indicators = indicators == null ? List.of() : List.copyOf(indicators);
            missing = missing == null ? List.of() : List.copyOf(missing);
        }
    }
}
