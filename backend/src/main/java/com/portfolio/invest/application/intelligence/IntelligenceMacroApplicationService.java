package com.portfolio.invest.application.intelligence;

import com.portfolio.invest.application.intelligence.IntelligenceQueryService.MacroBriefResult;
import com.portfolio.invest.application.intelligence.IntelligenceQueryService.MacroIndicatorView;
import com.portfolio.invest.application.intelligence.IntelligenceQueryService.PolicyItemView;
import com.portfolio.invest.application.intelligence.IntelligenceQueryService.SeriesPoint;
import com.portfolio.invest.domain.intelligence.MacroPoint;
import com.portfolio.invest.domain.intelligence.PageQuery;
import com.portfolio.invest.domain.intelligence.PageResult;
import com.portfolio.invest.domain.intelligence.PolicyEvent;
import com.portfolio.invest.domain.intelligence.PolicyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 宏观读端点族（MS-22 Task 6 宏观简报 + D22 宏观页聚合 macroOverview/calendar）。
 * 2026-10-12 按读端点族拆分自原 {@link IntelligenceQueryService}（纯搬移，行为不变）；
 * 本类为门面手工装配、非 Spring bean。indicators 解析 / policyDays 夹紧 1..90（缺省 30）
 * 单点收口在本服务。
 */
public class IntelligenceMacroApplicationService {

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

    /** 宏观日历缺省天数（D22：政策库区块日历卡「未来 7 天预期发布」）。 */
    static final int DEFAULT_CALENDAR_DAYS = 7;

    private final MacroQueryService macroQueryService;
    private final PolicyRepository policyRepository;
    private final Clock clock;

    public IntelligenceMacroApplicationService(MacroQueryService macroQueryService,
                                               PolicyRepository policyRepository,
                                               Clock clock) {
        this.macroQueryService = macroQueryService;
        this.policyRepository = policyRepository;
        this.clock = clock;
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
                1, IntelligenceSearchApplicationService.MAX_LIMIT, null, null, null,
                today.minusDays(policyDays - 1L), today, null, null, null, null));
        List<PolicyItemView> policies = page.items().stream().map(PolicyItemView::of).toList();
        return new MacroBriefResult(indicators, policies, page.total(), missing,
                Instant.now(clock));
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
}
