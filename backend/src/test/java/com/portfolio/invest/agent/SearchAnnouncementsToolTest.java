package com.portfolio.invest.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.portfolio.invest.application.allocation.AllocationApplicationService;
import com.portfolio.invest.application.intelligence.AnnouncementScope;
import com.portfolio.invest.application.intelligence.IntelligenceQueryService;
import com.portfolio.invest.application.portfolio.PortfolioApplicationService;
import com.portfolio.invest.domain.intelligence.AnnouncementMetrics;
import com.portfolio.invest.domain.intelligence.AnnouncementType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * search_announcements 只读工具（MS-21 Task 8）：JSON 输出契约（items + total /
 * 空结果三态话术——scope 引导语 / 该标的无公告 / 检索无结果）、scope 三态透传
 * （解析与过滤在 IntelligenceQueryService，工具只组装过滤器）、userId 构造传导、
 * 日期/类型前置校验与 run() 兜底。
 */
class SearchAnnouncementsToolTest {

    private IntelligenceQueryService intelligenceQuery;
    private UserInvestTools tools;

    @BeforeEach
    void setUp() {
        intelligenceQuery = mock(IntelligenceQueryService.class);
        // 复刻 Spring Boot 对 ObjectMapper 的配置（JavaTimeModule + ISO 日期，不写时间戳）
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        tools = new UserInvestTools(7L, mock(PortfolioApplicationService.class),
                mock(AllocationApplicationService.class), intelligenceQuery, mapper);
    }

    private static IntelligenceQueryService.AnnouncementItemView item(String title, String stockCode,
            List<AnnouncementType> annTypes) {
        return new IntelligenceQueryService.AnnouncementItemView(title, stockCode, "贵州茅台",
                annTypes, "公司公告",
                new AnnouncementMetrics(new BigDecimal("128.56"), new BigDecimal("31.2"),
                        new BigDecimal("25.3"), new BigDecimal("30.05"), null, "每10股派2元", List.of()),
                "https://x/1.pdf", Instant.parse("2026-09-28T13:00:00Z"));
    }

    @DisplayName("正常结果：items 数组 + total，条目八字段齐备（含 metrics 六字段与类型标签）")
    @Test
    void givenItems_whenSearchAnnouncements_thenJsonHasItemsAndTotal() {
        when(intelligenceQuery.searchAnnouncements(eq(7L), any())).thenReturn(
                new IntelligenceQueryService.AnnouncementSearchResult(List.of(
                        item("贵州茅台 2026 年半年度报告", "600519",
                                List.of(AnnouncementType.PERIODIC_REPORT, AnnouncementType.OTHER))),
                        17, null));

        String json = tools.searchAnnouncements("600519", null, "2026-07-01", null, null, null, null);

        assertThat(json)
                .contains("\"items\":[")
                .contains("\"total\":17")
                .contains("贵州茅台 2026 年半年度报告")
                .contains("\"stockCode\":\"600519\"").contains("\"stockName\":\"贵州茅台\"")
                .contains("\"annTypes\":[\"PERIODIC_REPORT\",\"OTHER\"]")
                .contains("\"annTypeSource\":\"公司公告\"")
                .contains("\"revenueYi\":128.56").contains("\"netProfitYi\":31.2")
                .contains("\"netProfitYoyPct\":25.3").contains("\"deductedProfitYi\":30.05")
                .contains("\"dividendDesc\":\"每10股派2元\"")
                .contains("\"pdfUrl\":\"https://x/1.pdf\"")
                .contains("\"publishedAt\":\"2026-09-28T13:00:00Z\"")
                .doesNotContain("\"message\"");
    }

    @DisplayName("scope 三态透传：holdings/subscription 原样传导（随 userId），缺省归 all")
    @Test
    void givenScopeVariants_whenSearchAnnouncements_thenScopeParsedAndPassedWithUserId() {
        when(intelligenceQuery.searchAnnouncements(any(), any())).thenReturn(
                new IntelligenceQueryService.AnnouncementSearchResult(List.of(), 0, null));

        tools.searchAnnouncements(null, null, null, null, null, "holdings", null);
        tools.searchAnnouncements(null, null, null, null, null, "subscription", null);
        tools.searchAnnouncements(null, null, null, null, null, null, null);

        verify(intelligenceQuery).searchAnnouncements(eq(7L),
                argThat(f -> f.scope() == AnnouncementScope.HOLDINGS));
        verify(intelligenceQuery).searchAnnouncements(eq(7L),
                argThat(f -> f.scope() == AnnouncementScope.SUBSCRIPTION));
        verify(intelligenceQuery).searchAnnouncements(eq(7L),
                argThat(f -> f.scope() == AnnouncementScope.ALL));
    }

    @DisplayName("scope 空集：服务 scopeMessage 透传为 message，items 空、不带 total")
    @Test
    void givenScopeEmptyMessage_whenSearchAnnouncements_thenMessageRendered() {
        when(intelligenceQuery.searchAnnouncements(any(), any())).thenReturn(
                new IntelligenceQueryService.AnnouncementSearchResult(List.of(), 0, "未设置订阅标的"));

        String json = tools.searchAnnouncements(null, null, null, null, null, "subscription", null);

        assertThat(json)
                .contains("\"items\":[]")
                .contains("\"message\":\"未设置订阅标的\"")
                .doesNotContain("\"total\"");
    }

    @DisplayName("空结果两态话术：带 stock 参数「该标的无公告」，不带「检索无结果」")
    @Test
    void givenEmptyItems_whenSearchAnnouncements_thenWordingByStockParam() {
        when(intelligenceQuery.searchAnnouncements(any(), any())).thenReturn(
                new IntelligenceQueryService.AnnouncementSearchResult(List.of(), 0, null));

        String withStock = tools.searchAnnouncements("600519", null, null, null, null, null, null);
        String withoutStock = tools.searchAnnouncements(null, null, null, null, "回购", null, null);

        assertThat(withStock).contains("\"message\":\"" + UserInvestTools.STOCK_EMPTY_MESSAGE + "\"");
        assertThat(withoutStock).contains("\"message\":\"" + UserInvestTools.NO_RESULT_MESSAGE + "\"");
    }

    @DisplayName("七参数透传：过滤器逐字段原样组装（夹紧在服务内，limit 999 不在工具层截断）")
    @Test
    void givenAllParams_whenSearchAnnouncements_thenFilterPassedThrough() {
        when(intelligenceQuery.searchAnnouncements(any(), any())).thenReturn(
                new IntelligenceQueryService.AnnouncementSearchResult(List.of(), 0, null));

        tools.searchAnnouncements("600519", "BUYBACK", "2026-09-01", "2026-09-28", "回购",
                "subscription", 999);

        verify(intelligenceQuery).searchAnnouncements(eq(7L), argThat(f ->
                "回购".equals(f.q()) && "600519".equals(f.stock())
                        && f.type() == AnnouncementType.BUYBACK
                        && LocalDate.of(2026, 9, 1).equals(f.from())
                        && LocalDate.of(2026, 9, 28).equals(f.to())
                        && f.scope() == AnnouncementScope.SUBSCRIPTION
                        && Integer.valueOf(999).equals(f.limit())));
    }

    @DisplayName("from/to 非法日期：参数错误 JSON 不抛（提示 yyyy-MM-dd 格式）")
    @Test
    void givenMalformedDate_whenSearchAnnouncements_thenFormatErrorJson() {
        String json = tools.searchAnnouncements(null, null, "2026/09/01", null, null, null, null);

        assertThat(json).contains("\"error\"").contains("yyyy-MM-dd");
    }

    @DisplayName("type 非法枚举：参数错误 JSON 列出合法取值（LLM 自纠），不走 run 兜底")
    @Test
    void givenUnknownType_whenSearchAnnouncements_thenTypeErrorJson() {
        String json = tools.searchAnnouncements(null, "回购", null, null, null, null, null);

        assertThat(json)
                .contains("\"error\"").contains("未知公告类型：回购")
                .contains("BUYBACK").contains("EARNINGS_FORECAST")
                .contains("请修正 type 后重试");
    }

    @DisplayName("服务异常：run 兜底结构化错误，绝不抛")
    @Test
    void givenServiceBlowsUp_whenSearchAnnouncements_thenStructuredErrorNotThrown() {
        when(intelligenceQuery.searchAnnouncements(any(), any()))
                .thenThrow(new IllegalStateException("db down"));

        String json = tools.searchAnnouncements("600519", null, null, null, null, null, null);

        assertThat(json).contains("\"error\":\"工具执行失败\"").contains("\"hint\":\"请稍后重试\"");
    }
}
