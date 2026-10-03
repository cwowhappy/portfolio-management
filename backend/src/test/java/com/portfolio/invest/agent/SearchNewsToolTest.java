package com.portfolio.invest.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.portfolio.invest.application.industry.IndustryApplicationService;
import com.portfolio.invest.application.intelligence.IntelligenceQueryService;
import com.portfolio.invest.application.intelligence.NewsSearchFilter;
import com.portfolio.invest.application.market.FinancialQueryService;
import com.portfolio.invest.application.market.MarketDataService;
import com.portfolio.invest.application.screening.ScreeningApplicationService;
import com.portfolio.invest.application.valuation.ValuationApplicationService;
import com.portfolio.invest.domain.intelligence.Direction;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * search_news 只读工具（MS-20 Task 12）：JSON 输出契约（items + total / 空结果话术）、
 * 参数透传（夹紧归 IntelligenceQueryService，工具只组装过滤器）、日期格式校验与 run() 兜底。
 */
class SearchNewsToolTest {

    private static final String EMPTY_MESSAGE = "该条件下暂无情报（新闻仅保留 90 天内）";

    private IntelligenceQueryService intelligenceQuery;
    private InvestTools tools;

    @BeforeEach
    void setUp() {
        intelligenceQuery = mock(IntelligenceQueryService.class);
        // 复刻 Spring Boot 对 ObjectMapper 的配置（JavaTimeModule + ISO 日期，不写时间戳）
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        tools = new InvestTools(
                mock(MarketDataService.class),
                mock(ValuationApplicationService.class),
                mock(ScreeningApplicationService.class),
                mock(FinancialQueryService.class),
                mock(IndustryApplicationService.class),
                intelligenceQuery,
                mapper);
    }

    private static IntelligenceQueryService.NewsItemView item(String title, Direction direction,
            List<String> keyNumbers) {
        return new IntelligenceQueryService.NewsItemView(
                title, "AI 摘要", direction, 72, keyNumbers, List.of("600519"),
                "https://x/1", Instant.parse("2026-09-28T13:00:00Z"));
    }

    @DisplayName("正常结果：items 数组 + total，条目八字段齐备（含关键数字）")
    @Test
    void givenItems_whenSearchNews_thenJsonHasItemsAndTotal() {
        when(intelligenceQuery.searchNews(any())).thenReturn(new IntelligenceQueryService.NewsSearchResult(
                List.of(item("茅台三季报预增", Direction.BULLISH, List.of("Q3 净利润同比 +25.3%")),
                        item("白酒板块承压", Direction.BEARISH, List.of())), 17));

        String json = tools.searchNews("茅台", "600519", null, "2026-09-01", null, 60, null);

        assertThat(json)
                .contains("\"items\":[")
                .contains("\"total\":17")
                .contains("茅台三季报预增").contains("白酒板块承压")
                .contains("AI 摘要")
                .contains("\"direction\":\"BULLISH\"").contains("\"direction\":\"BEARISH\"")
                .contains("\"importance\":72")
                .contains("\"keyNumbers\":[\"Q3 净利润同比 +25.3%\"]")
                .contains("\"stockCodes\":[\"600519\"]")
                .contains("https://x/1")
                .contains("2026-09-28T13:00:00Z")
                .doesNotContain(EMPTY_MESSAGE);
    }

    @DisplayName("空结果：items 空数组 + 90 天话术，不带 total")
    @Test
    void givenEmptyResult_whenSearchNews_thenEmptyItemsWithMessage() {
        when(intelligenceQuery.searchNews(any()))
                .thenReturn(new IntelligenceQueryService.NewsSearchResult(List.of(), 0));

        String json = tools.searchNews("锂矿", null, null, null, null, null, null);

        assertThat(json)
                .contains("\"items\":[]")
                .contains("\"message\":\"" + EMPTY_MESSAGE + "\"")
                .doesNotContain("\"total\"");
    }

    @DisplayName("七参数透传：过滤器逐字段原样组装（夹紧在服务内，limit 999 不在工具层截断）")
    @Test
    void givenAllParams_whenSearchNews_thenFilterPassedThrough() {
        when(intelligenceQuery.searchNews(any())).thenReturn(
                new IntelligenceQueryService.NewsSearchResult(List.of(), 0));

        tools.searchNews("机器人", "300024", "801140", "2026-09-01", "2026-09-28", 40, 999);

        verify(intelligenceQuery).searchNews(argThat(f ->
                "机器人".equals(f.q()) && "300024".equals(f.stock()) && "801140".equals(f.industry())
                        && LocalDate.of(2026, 9, 1).equals(f.from())
                        && LocalDate.of(2026, 9, 28).equals(f.to())
                        && Integer.valueOf(40).equals(f.minImportance())
                        && Integer.valueOf(999).equals(f.limit())));
    }

    @DisplayName("from/to 非法日期：参数错误 JSON 不抛（提示 yyyy-MM-dd 格式）")
    @Test
    void givenMalformedDate_whenSearchNews_thenFormatErrorJson() {
        String json = tools.searchNews(null, null, null, "2026/09/01", null, null, null);

        assertThat(json).contains("\"error\"").contains("yyyy-MM-dd");
    }

    @DisplayName("服务异常：run 兜底结构化错误，绝不抛")
    @Test
    void givenServiceBlowsUp_whenSearchNews_thenStructuredErrorNotThrown() {
        when(intelligenceQuery.searchNews(any())).thenThrow(new IllegalStateException("db down"));

        String json = tools.searchNews("茅台", null, null, null, null, null, null);

        assertThat(json).contains("\"error\":\"工具执行失败\"").contains("\"hint\":\"请稍后重试\"");
    }
}
