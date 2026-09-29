package com.portfolio.invest.application.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.invest.domain.intelligence.Direction;
import com.portfolio.invest.domain.intelligence.ExtractStatus;
import com.portfolio.invest.domain.intelligence.NewsRecord;
import com.portfolio.invest.domain.intelligence.NewsRepository;
import com.portfolio.invest.domain.intelligence.PageResult;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 情报检索用例（MS-20 Task 12 最小版）：limit 夹紧 1..20 + 过滤器→PageQuery 映射 +
 * NewsRecord→条目视图映射（summary 取 AI 摘要、缺席退化源站摘要）。P4 扩四区块查询。
 */
class IntelligenceQueryServiceTest {

    private final NewsRepository newsRepository = mock(NewsRepository.class);
    private IntelligenceQueryService service;

    @BeforeEach
    void setUp() {
        service = new IntelligenceQueryService(newsRepository);
    }

    /** 已抽取 SUCCESS 的完整记录（前半 raw + 后半抽取侧全有值）。 */
    private static NewsRecord extractedRecord() {
        return new NewsRecord(1L, "eastmoney", "ext-1", "茅台三季报预增", "源站摘要",
                Instant.parse("2026-09-28T13:00:00Z"), "https://x/1", "[{\"code\":\"600519\"}]",
                Instant.parse("2026-09-28T14:00:00Z"),
                "earnings_up", List.of("600519"), List.of("801140"), "AI 摘要",
                Direction.BULLISH, List.of("净利润 +25%"), 72,
                ExtractStatus.SUCCESS, "deepseek-chat", Instant.parse("2026-09-28T15:00:00Z"));
    }

    /** 尚无抽取行的 raw（抽取侧字段全 null）。 */
    private static NewsRecord rawOnlyRecord() {
        return new NewsRecord(2L, "eastmoney", "ext-2", "白酒板块承压", "源站原始摘要",
                Instant.parse("2026-09-27T13:00:00Z"), null, "[]",
                Instant.parse("2026-09-27T14:00:00Z"),
                null, List.of(), List.of(), null, null, List.of(), null, null, null, null);
    }

    @Test
    @DisplayName("给定过滤条件与默认 limit，when检索，then PageQuery 逐字段映射且 page=1")
    void givenFilter_whenSearchNews_thenMapsToPageQuery() {
        when(newsRepository.search(any())).thenReturn(new PageResult<>(List.of(), 0, 1, 10));

        service.searchNews(new NewsSearchFilter("机器人", "300024", "801140",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 28), 40, null));

        verifyMapping(1, 10);
    }

    @Test
    @DisplayName("limit 夹紧 1..20：null→10、999→20、0→1")
    void givenLimitVariants_whenSearchNews_thenClampedIntoPageQuery() {
        when(newsRepository.search(any())).thenReturn(new PageResult<>(List.of(), 0, 1, 10));

        service.searchNews(new NewsSearchFilter(null, null, null, null, null, null, null));
        service.searchNews(new NewsSearchFilter(null, null, null, null, null, null, 999));
        service.searchNews(new NewsSearchFilter(null, null, null, null, null, null, 0));

        verifyMapping(1, 10);
        verifyMapping(1, 20);
        verifyMapping(1, 1);
    }

    @Test
    @DisplayName("条目映射：AI 摘要优先、缺席退化源站摘要；total 透传 PageResult.total")
    void givenMixedRecords_whenSearchNews_thenViewPrefersExtractSummary() {
        when(newsRepository.search(any())).thenReturn(new PageResult<>(
                List.of(extractedRecord(), rawOnlyRecord()), 27, 1, 20));

        var result = service.searchNews(new NewsSearchFilter(null, null, null, null, null, null, null));

        assertThat(result.total()).isEqualTo(27);
        assertThat(result.items()).hasSize(2);
        var extracted = result.items().get(0);
        assertThat(extracted.title()).isEqualTo("茅台三季报预增");
        assertThat(extracted.summary()).isEqualTo("AI 摘要");
        assertThat(extracted.direction()).isEqualTo(Direction.BULLISH);
        assertThat(extracted.importance()).isEqualTo(72);
        assertThat(extracted.keyNumbers()).as("关键数字直传（key_numbers JSONB 已有列，全链补消费方）")
                .containsExactly("净利润 +25%");
        assertThat(extracted.stockCodes()).containsExactly("600519");
        assertThat(extracted.url()).isEqualTo("https://x/1");
        assertThat(extracted.publishedAt()).isEqualTo(Instant.parse("2026-09-28T13:00:00Z"));
        var rawOnly = result.items().get(1);
        assertThat(rawOnly.summary()).as("无抽取行时退化源站摘要").isEqualTo("源站原始摘要");
        assertThat(rawOnly.direction()).isNull();
        assertThat(rawOnly.keyNumbers()).as("未抽取条目关键数字归一空数组（null 安全）").isEmpty();
    }

    /** 断言仓库收到 page/pageSize 恰为期望值的 PageQuery（其余字段不限）。 */
    private void verifyMapping(int page, int pageSize) {
        org.mockito.Mockito.verify(newsRepository).search(argThat(q ->
                q.page() == page && q.pageSize() == pageSize));
    }
}
