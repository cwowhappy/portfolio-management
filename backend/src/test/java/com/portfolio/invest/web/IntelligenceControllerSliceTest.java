package com.portfolio.invest.web;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.application.intelligence.AnnouncementFilter;
import com.portfolio.invest.application.intelligence.BriefFilter;
import com.portfolio.invest.application.intelligence.IntelligenceQueryService;
import com.portfolio.invest.application.intelligence.IntelligenceQueryService.AnnouncementItemView;
import com.portfolio.invest.application.intelligence.IntelligenceQueryService.NewsItemView;
import com.portfolio.invest.application.intelligence.IntelligenceViews;
import com.portfolio.invest.application.intelligence.IntelligenceViews.BriefDetailView;
import com.portfolio.invest.application.intelligence.IntelligenceViews.CalendarView;
import com.portfolio.invest.application.intelligence.IntelligenceViews.MacroOverviewView;
import com.portfolio.invest.application.intelligence.IntelligenceViews.StockIntelView;
import com.portfolio.invest.application.intelligence.NewsFilter;
import com.portfolio.invest.application.intelligence.PolicyFilter;
import com.portfolio.invest.domain.intelligence.AnnouncementType;
import com.portfolio.invest.domain.intelligence.BriefStatus;
import com.portfolio.invest.domain.intelligence.Direction;
import com.portfolio.invest.domain.intelligence.IntelligenceErrorCode;
import com.portfolio.invest.domain.intelligence.IntelligenceException;
import com.portfolio.invest.domain.intelligence.PolicyDirection;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 情报工作台只读端点切片（/api/intelligence/**，D20 §4.3）：参数逐字段透传服务层
 * （过滤/分页组装 Filter record，夹紧在服务内——web 不重复防御）、PageView 信封
 * 序列化回显（夹紧后的 page/pageSize 由服务返回值承载）、日期/枚举参数格式错
 * 走既有 MethodArgumentTypeMismatchException → 400 INVALID_REQUEST 分支（照
 * ScreeningController 枚举直绑先例）、briefDetail 缺档 IntelligenceException
 * NOT_FOUND → 404（GlobalExceptionHandler 情报分支）。服务打桩；登录态即可
 * （无用户数据），与 /api/screening 公开前例的差异仅在访问控制（本前缀不在白名单）。
 */
@WebMvcTest(IntelligenceController.class)
@WithMockUser
class IntelligenceControllerSliceTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private IntelligenceQueryService service;

    private static final Instant T = Instant.parse("2026-09-30T08:00:00Z");

    @Test
    @DisplayName("GET /news 全过滤参数：逐字段透传 NewsFilter 且 PageView 信封回显夹紧后分页")
    void givenFullFilters_whenNews_thenFilterPassthroughAndEnvelopeEcho() throws Exception {
        var item = new NewsItemView("美联储降息", "50bp 幅度落地", Direction.BULLISH, 90,
                List.of("降息 50bp"), List.of("600519"), "https://example.com/n1", T);
        when(service.newsPage(new NewsFilter("降息", "600519", "801140",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), 60, 3, 50)))
                .thenReturn(new IntelligenceViews.PageView<>(List.of(item), 123, 3, 50));

        mvc.perform(get("/api/intelligence/news")
                        .param("q", "降息").param("stock", "600519").param("industry", "801140")
                        .param("from", "2026-09-01").param("to", "2026-09-30")
                        .param("minImportance", "60").param("page", "3").param("pageSize", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title").value("美联储降息"))
                .andExpect(jsonPath("$.items[0].direction").value("BULLISH"))
                .andExpect(jsonPath("$.items[0].importance").value(90))
                .andExpect(jsonPath("$.items[0].stockCodes[0]").value("600519"))
                .andExpect(jsonPath("$.total").value(123))
                .andExpect(jsonPath("$.page").value(3))
                .andExpect(jsonPath("$.pageSize").value(50));

        verify(service).newsPage(new NewsFilter("降息", "600519", "801140",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), 60, 3, 50));
    }

    @Test
    @DisplayName("GET /news 无参：page/pageSize 缺省 1/20，其余过滤字段为 null")
    void givenNoParams_whenNews_thenDefaultsPassthrough() throws Exception {
        when(service.newsPage(new NewsFilter(null, null, null, null, null, null, 1, 20)))
                .thenReturn(new IntelligenceViews.PageView<NewsItemView>(List.of(), 0, 1, 20));

        mvc.perform(get("/api/intelligence/news"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(20));
    }

    @Test
    @DisplayName("GET /news 日期格式错：400 INVALID_REQUEST（类型不匹配分支）且不触达服务")
    void givenMalformedFrom_whenNews_then400() throws Exception {
        mvc.perform(get("/api/intelligence/news").param("from", "2026/09/01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("GET /announcements 全过滤参数：type 枚举与 major 透传 AnnouncementFilter")
    void givenFullFilters_whenAnnouncements_thenFilterPassthrough() throws Exception {
        var item = new AnnouncementItemView("三季度报告", "600519", "贵州茅台",
                List.of(AnnouncementType.PERIODIC_REPORT), "定期报告", null,
                "https://example.com/a.pdf", T);
        when(service.announcementsPage(new AnnouncementFilter("三季度", "600519",
                AnnouncementType.PERIODIC_REPORT, LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 30), true, 2, 30)))
                .thenReturn(new IntelligenceViews.PageView<>(List.of(item), 8, 2, 30));

        mvc.perform(get("/api/intelligence/announcements")
                        .param("stock", "600519").param("type", "PERIODIC_REPORT")
                        .param("q", "三季度").param("from", "2026-09-01").param("to", "2026-09-30")
                        .param("major", "true").param("page", "2").param("pageSize", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title").value("三季度报告"))
                .andExpect(jsonPath("$.items[0].annTypes[0]").value("PERIODIC_REPORT"))
                .andExpect(jsonPath("$.total").value(8))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.pageSize").value(30));
    }

    @Test
    @DisplayName("GET /announcements type 非法枚举：400 INVALID_REQUEST 且不触达服务")
    void givenIllegalType_whenAnnouncements_then400() throws Exception {
        mvc.perform(get("/api/intelligence/announcements").param("type", "NOT_A_TYPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("GET /policies direction 过滤：枚举透传 PolicyFilter")
    void givenDirection_whenPolicies_thenFilterPassthrough() throws Exception {
        var item = new IntelligenceQueryService.PolicyItemView("降准落地",
                PolicyDirection.EASING, null, List.of("货币"), "全面降准 0.5pct",
                null, true, "https://example.com/p1", T);
        when(service.policies(new PolicyFilter("降准", LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 30), PolicyDirection.EASING, 1, 20)))
                .thenReturn(new IntelligenceViews.PageView<>(List.of(item), 1, 1, 20));

        mvc.perform(get("/api/intelligence/policies")
                        .param("q", "降准").param("from", "2026-09-01").param("to", "2026-09-30")
                        .param("direction", "EASING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title").value("降准落地"))
                .andExpect(jsonPath("$.items[0].direction").value("EASING"))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    @DisplayName("GET /policies direction 非法枚举：400 INVALID_REQUEST 且不触达服务")
    void givenIllegalDirection_whenPolicies_then400() throws Exception {
        mvc.perform(get("/api/intelligence/policies").param("direction", "SIDEWAYS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("GET /briefs 归档列表：from/to/stock/q 透传 BriefFilter")
    void givenFilters_whenBriefs_thenFilterPassthrough() throws Exception {
        var item = new IntelligenceViews.BriefItemView(LocalDate.of(2026, 9, 30),
                BriefStatus.GENERATED, List.of("600519"), "deepseek-chat", T);
        when(service.briefs(new BriefFilter("茅台", LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 30), "600519", 1, 20)))
                .thenReturn(new IntelligenceViews.PageView<>(List.of(item), 1, 1, 20));

        mvc.perform(get("/api/intelligence/briefs")
                        .param("from", "2026-09-01").param("to", "2026-09-30")
                        .param("stock", "600519").param("q", "茅台"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].tradeDate").value("2026-09-30"))
                .andExpect(jsonPath("$.items[0].status").value("GENERATED"))
                .andExpect(jsonPath("$.items[0].topStocks[0]").value("600519"))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    @DisplayName("GET /briefs/{tradeDate} 有档：全字段详情回显（含 contentMd 正文）")
    void givenBriefExists_whenBriefDetail_then200WithContent() throws Exception {
        when(service.briefDetail(LocalDate.of(2026, 9, 30)))
                .thenReturn(new BriefDetailView(LocalDate.of(2026, 9, 30), "# 盘前简报\n正文",
                        List.of("600519"), BriefStatus.GENERATED, null, "deepseek-chat", T));

        mvc.perform(get("/api/intelligence/briefs/2026-09-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tradeDate").value("2026-09-30"))
                .andExpect(jsonPath("$.contentMd").value("# 盘前简报\n正文"))
                .andExpect(jsonPath("$.status").value("GENERATED"))
                .andExpect(jsonPath("$.model").value("deepseek-chat"))
                .andExpect(jsonPath("$.failReason").value(nullValue()));
    }

    @Test
    @DisplayName("GET /briefs/{tradeDate} 缺档：IntelligenceException NOT_FOUND → 404")
    void givenMissingBrief_whenBriefDetail_then404() throws Exception {
        when(service.briefDetail(LocalDate.of(2026, 10, 1)))
                .thenThrow(new IntelligenceException(IntelligenceErrorCode.NOT_FOUND,
                        "当日简报不存在：2026-10-01"));

        mvc.perform(get("/api/intelligence/briefs/2026-10-01"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("当日简报不存在：2026-10-01"));
    }

    @Test
    @DisplayName("GET /briefs/{tradeDate} 日期格式错：400 INVALID_REQUEST")
    void givenMalformedTradeDate_whenBriefDetail_then400() throws Exception {
        mvc.perform(get("/api/intelligence/briefs/2026-09-99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("GET /macro indicators/limit 透传 macroOverview，缺失指标显式列出")
    void givenIndicatorsAndLimit_whenMacro_thenPassthroughAndShape() throws Exception {
        var indicator = new IntelligenceQueryService.MacroIndicatorView(
                "CPI", new BigDecimal("0.4"), new BigDecimal("0.5"), "2026-08", "MONTH",
                List.of(new IntelligenceQueryService.SeriesPoint("2026-08", new BigDecimal("0.4"))));
        when(service.macroOverview("CPI,GDP", 12))
                .thenReturn(new MacroOverviewView(List.of(indicator), List.of("GDP"), T));

        mvc.perform(get("/api/intelligence/macro")
                        .param("indicators", "CPI,GDP").param("limit", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indicators[0].indicator").value("CPI"))
                .andExpect(jsonPath("$.indicators[0].period").value("2026-08"))
                .andExpect(jsonPath("$.indicators[0].series[0].period").value("2026-08"))
                .andExpect(jsonPath("$.missing[0]").value("GDP"))
                .andExpect(jsonPath("$.generatedAt").value("2026-09-30T08:00:00Z"));
    }

    @Test
    @DisplayName("GET /macro 无参：indicators/limit 传 null（服务层取全七缺省与 5 期缺省）")
    void givenNoParams_whenMacro_thenNullPassthrough() throws Exception {
        when(service.macroOverview(null, null)).thenReturn(new MacroOverviewView(List.of(), List.of(), T));

        mvc.perform(get("/api/intelligence/macro"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indicators").isEmpty())
                .andExpect(jsonPath("$.missing").isEmpty());

        verify(service).macroOverview(null, null);
    }

    @Test
    @DisplayName("GET /macro/calendar?days=14 透传；条目含预期发布日与最近维护时间（D22）")
    void givenDays_whenMacroCalendar_thenPassthroughAndShape() throws Exception {
        when(service.calendar(14)).thenReturn(List.of(new CalendarView("CPI",
                LocalDate.of(2026, 10, 9), "MONTH", "国家统计局", T)));

        mvc.perform(get("/api/intelligence/macro/calendar").param("days", "14"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].indicator").value("CPI"))
                .andExpect(jsonPath("$[0].expectedDate").value("2026-10-09"))
                .andExpect(jsonPath("$[0].frequency").value("MONTH"))
                .andExpect(jsonPath("$[0].sourceSite").value("国家统计局"))
                .andExpect(jsonPath("$[0].updatedAt").value("2026-09-30T08:00:00Z"));
    }

    @Test
    @DisplayName("GET /macro/calendar 无参：days 传 null（服务层缺省 7 天）")
    void givenNoParams_whenMacroCalendar_thenNullPassthrough() throws Exception {
        when(service.calendar(null)).thenReturn(List.of());

        mvc.perform(get("/api/intelligence/macro/calendar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        verify(service).calendar(null);
    }

    @Test
    @DisplayName("GET /stocks/{code} 标的聚合：三路计数与政策引导话术回显（F17）")
    void givenStockCode_whenStockIntel_thenAggregationEcho() throws Exception {
        when(service.stockIntel("600519")).thenReturn(new StockIntelView("600519", 12,
                List.of(), 3, List.of(), 0,
                "政策事件不按标的归集——请到「政策库」按方向/日期/关键词检索", false));

        mvc.perform(get("/api/intelligence/stocks/600519"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockCode").value("600519"))
                .andExpect(jsonPath("$.newsTotal").value(12))
                .andExpect(jsonPath("$.announcementTotal").value(3))
                .andExpect(jsonPath("$.policyTotal").value(0))
                .andExpect(jsonPath("$.empty").value(false))
                .andExpect(jsonPath("$.policyNote").value(
                        "政策事件不按标的归集——请到「政策库」按方向/日期/关键词检索"));
    }

    @Test
    @WithAnonymousUser
    @DisplayName("未登录访问只读端点：401（/api/intelligence/** 不在公开白名单）")
    void givenAnonymous_whenAccessReadEndpoint_then401() throws Exception {
        mvc.perform(get("/api/intelligence/news"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/intelligence/stocks/600519"))
                .andExpect(status().isUnauthorized());
    }
}
