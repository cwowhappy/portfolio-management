package com.portfolio.invest.web;

import com.portfolio.invest.application.intelligence.AnnouncementFilter;
import com.portfolio.invest.application.intelligence.BriefFilter;
import com.portfolio.invest.application.intelligence.IntelligenceQueryService;
import com.portfolio.invest.application.intelligence.IntelligenceQueryService.AnnouncementItemView;
import com.portfolio.invest.application.intelligence.IntelligenceQueryService.NewsItemView;
import com.portfolio.invest.application.intelligence.IntelligenceQueryService.PolicyItemView;
import com.portfolio.invest.application.intelligence.IntelligenceViews;
import com.portfolio.invest.application.intelligence.IntelligenceViews.BriefDetailView;
import com.portfolio.invest.application.intelligence.IntelligenceViews.BriefItemView;
import com.portfolio.invest.application.intelligence.IntelligenceViews.CalendarView;
import com.portfolio.invest.application.intelligence.IntelligenceViews.MacroOverviewView;
import com.portfolio.invest.application.intelligence.IntelligenceViews.StockIntelView;
import com.portfolio.invest.application.intelligence.NewsFilter;
import com.portfolio.invest.application.intelligence.PolicyFilter;
import com.portfolio.invest.domain.intelligence.AnnouncementType;
import com.portfolio.invest.domain.intelligence.PolicyDirection;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 情报工作台只读端点（/api/intelligence/**，D20 §4.3 + F17 标的聚合 + D22 日历）：
 * 只做路由与参数组装（逐字段透传 {@code *Filter} record，夹紧在服务层 PageQuery
 * 收口——web 不重复防御）；登录态即可访问（无用户数据维度，照 /api/screening
 * 只读先例，但本前缀不在公开白名单）。日期参数走 ISO yyyy-MM-dd 缺省转换
 * （照 JournalController.timeline 先例），格式错/枚举非法 → 既有
 * MethodArgumentTypeMismatchException 400 INVALID_REQUEST 分支；缺档 404 由
 * GlobalExceptionHandler 情报分支（NOT_FOUND）翻译。
 */
@RestController
@RequestMapping("/api/intelligence")
public class IntelligenceController {

    private final IntelligenceQueryService service;

    public IntelligenceController(IntelligenceQueryService service) {
        this.service = service;
    }

    /** 新闻流（D20 §4.3）：q 标题近似 / stock·industry JSONB 包含 / from·to 闭区间 / minImportance 下限。 */
    @GetMapping("/news")
    public IntelligenceViews.PageView<NewsItemView> news(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String stock,
            @RequestParam(required = false) String industry,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) Integer minImportance,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return service.newsPage(new NewsFilter(q, stock, industry, from, to,
                minImportance, page, pageSize));
    }

    /** 公告流（含 metrics 业绩要点）：type 为 {@link AnnouncementType} 枚举名、major 仅重大公告。 */
    @GetMapping("/announcements")
    public IntelligenceViews.PageView<AnnouncementItemView> announcements(
            @RequestParam(required = false) String stock,
            @RequestParam(required = false) AnnouncementType type,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) Boolean major,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return service.announcementsPage(new AnnouncementFilter(q, stock, type,
                from, to, major, page, pageSize));
    }

    /** 政策库：direction 为 {@link PolicyDirection} 枚举名（EASING/TIGHTENING/NEUTRAL）。 */
    @GetMapping("/policies")
    public IntelligenceViews.PageView<PolicyItemView> policies(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) PolicyDirection direction,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return service.policies(new PolicyFilter(q, from, to, direction, page, pageSize));
    }

    /** 简报归档列表（决策 #23 检索维度）：stock 命中 top_stocks 快照、q 走正文近似、日期为交易日闭区间。 */
    @GetMapping("/briefs")
    public IntelligenceViews.PageView<BriefItemView> briefs(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String stock,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return service.briefs(new BriefFilter(q, from, to, stock, page, pageSize));
    }

    /** 简报档详情（全字段含 content_md 正文）；当日无档 404（IntelligenceException NOT_FOUND）。 */
    @GetMapping("/briefs/{tradeDate}")
    public BriefDetailView briefDetail(@PathVariable LocalDate tradeDate) {
        return service.briefDetail(tradeDate);
    }

    /** 宏观总览：indicators 逗号分隔（空=全七缺省）、limit 序列期数（空=5，夹紧 1..60 在服务层）。 */
    @GetMapping("/macro")
    public MacroOverviewView macro(
            @RequestParam(required = false) String indicators,
            @RequestParam(required = false) Integer limit) {
        return service.macroOverview(indicators, limit);
    }

    /** 宏观日历（D22）：days 天内的预期发布日程（空=未来 7 天，夹紧 1..30 在服务层）。 */
    @GetMapping("/macro/calendar")
    public List<CalendarView> macroCalendar(@RequestParam(required = false) Integer days) {
        return service.calendar(days);
    }

    /** 标的聚合（F17）：三路命中计数 + 新闻/公告各路最新 5 条；空白码 422（INVALID_FILTER）。 */
    @GetMapping("/stocks/{code}")
    public StockIntelView stock(@PathVariable String code) {
        return service.stockIntel(code);
    }
}
