package com.portfolio.invest.application.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.intelligence.BriefStatus;
import com.portfolio.invest.domain.intelligence.DailyBrief;
import com.portfolio.invest.domain.intelligence.Direction;
import com.portfolio.invest.domain.intelligence.ExtractStatus;
import com.portfolio.invest.domain.intelligence.NewsRecord;
import com.portfolio.invest.domain.intelligence.NewsRepository;
import com.portfolio.invest.domain.intelligence.BriefRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 盘前简报生成服务单元切片（mock 仓库/LLM 端口/交易日历；Composer/Policy 用真实纯函数）：
 * 交易日假不生成、幂等跳过（当日已有档不重复生成）、选取窗口=昨日 15:00（上海）+
 * 候选池阈值=watch 线、GENERATED 六节结构+节导语+top_stocks 频次快照、
 * LLM empty/空串/异常一律降级无导语纯条目版（不 FAILED）、无候选 EMPTY_SIMPLE
 * （今日无重大情报+数据截止）、管线异常 FAILED 留档 fail_reason、调度顶层吞异常。
 * 附 BriefComposer 边界直测：null url/方向行格式与 top_stocks 20 截断。
 */
class BriefGenerationServiceTest {

    /** 上海 2026-09-29（周二）09:00（UTC 01:00）——当日与窗口锚点。 */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-29T01:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 29);
    /** 选取窗口起点：昨日 15:00 上海 = 2026-09-28T07:00:00Z。 */
    private static final Instant WINDOW_START = Instant.parse("2026-09-28T07:00:00Z");

    private static final String LEAD = "本节两到三句导语。";

    private final NewsRepository newsRepository = mock(NewsRepository.class);
    private final BriefRepository briefRepository = mock(BriefRepository.class);
    private final IntelligenceChatPort chatPort = mock(IntelligenceChatPort.class);
    private final TradingCalendarPort tradingCalendar = mock(TradingCalendarPort.class);
    private final InvestProperties props = new InvestProperties();
    private BriefGenerationService service;

    @BeforeEach
    void setUp() {
        service = new BriefGenerationService(newsRepository, briefRepository, chatPort,
                tradingCalendar, props, CLOCK);
        when(tradingCalendar.isTradingDay(any())).thenReturn(true);
        when(briefRepository.findByDate(any())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("给定非交易日，when生成简报，then不取数不调LLM不落档")
    void givenNonTradingDay_whenGenerate_thenSkipped() {
        when(tradingCalendar.isTradingDay(TODAY)).thenReturn(false);

        service.generateBrief();

        verify(newsRepository, never()).findMajorSince(any(), anyInt());
        verify(chatPort, never()).complete(any(), any());
        verify(briefRepository, never()).save(any());
    }

    @Test
    @DisplayName("给定当日已有简报档，when重跑生成，then幂等跳过不重复生成")
    void givenBriefAlreadyArchived_whenGenerateAgain_thenIdempotentSkip() {
        when(briefRepository.findByDate(TODAY)).thenReturn(Optional.of(DailyBrief.generated(
                TODAY, "# 旧档", List.of("600519"), "model", CLOCK.instant())));

        service.generateBrief();

        verify(newsRepository, never()).findMajorSince(any(), anyInt());
        verify(chatPort, never()).complete(any(), any());
        verify(briefRepository, never()).save(any());
    }

    @Test
    @DisplayName("给定昨夜今晨候选跨档，when生成简报，then窗口昨15点起取池按关注线GENERATED六节归档")
    void givenOvernightCandidates_whenGenerate_thenGeneratedWithSixSections() {
        when(newsRepository.findMajorSince(any(), anyInt())).thenReturn(List.of(
                news(1, 90, "MACRO", "600519"),
                news(2, 85, "EARNINGS", "600519", "300750"),
                news(3, 82, "M&A", "300750"),
                news(4, 60, "INDUSTRY"),
                news(5, 55, "LIQUIDITY", "600519"),
                news(6, 30, "EARNINGS")));
        when(chatPort.complete(any(), any()))
                .thenReturn(Optional.of(new IntelligenceChatPort.ChatOutcome(LEAD, 100)));

        service.generateBrief();

        // 候选池=昨 15:00 起按关注线（watchAt=50）取，档位切分交 policy；IGNORE(30) 不入池
        verify(newsRepository).findMajorSince(eq(WINDOW_START), eq(50));
        // 只为非空节生成导语：MACRO/COMPANY/INDUSTRY/LIQUIDITY 4 节
        verify(chatPort, times(4)).complete(any(), any());

        ArgumentCaptor<DailyBrief> captor = ArgumentCaptor.forClass(DailyBrief.class);
        verify(briefRepository).save(captor.capture());
        DailyBrief brief = captor.getValue();
        assertThat(brief.status()).isEqualTo(BriefStatus.GENERATED);
        assertThat(brief.tradeDate()).isEqualTo(TODAY);
        assertThat(brief.failReason()).isNull();
        assertThat(brief.model()).isEqualTo(props.getLlm().getModel());
        assertThat(brief.generatedAt()).isEqualTo(CLOCK.instant());

        // 5+1 节固定顺序全部在档
        assertThat(brief.contentMd()).contains("## 宏观与政策", "## 行业与板块", "## 公司要闻与公告",
                "## 资金与市场", "## 海外与大宗", "## 其他要闻");
        assertThat(brief.contentMd()).contains(LEAD); // 节导语入档
        // 行格式：- [标题](url)（重要度 85 · 利好）；空节占位
        assertThat(brief.contentMd()).contains("- [第2号情报](https://example.com/n2)（重要度 85 · 利好）");
        assertThat(brief.contentMd()).contains("（本节暂无入选条目）");

        // top_stocks = 选中条目 stock_codes 频次 top（600519×3 > 300750×2），IGNORE 条不入
        assertThat(brief.topStocks()).containsExactly("600519", "300750");
    }

    @Test
    @DisplayName("给定LLM通道未配置与空串输出，when生成简报，then降级无导语纯条目版仍GENERATED")
    void givenLlmEmptyOrBlank_whenGenerate_thenDegradeToItemsOnlyStillGenerated() {
        when(newsRepository.findMajorSince(any(), anyInt())).thenReturn(List.of(
                news(1, 90, "MACRO"), news(2, 85, "EARNINGS"))); // 两个非空节
        when(chatPort.complete(any(), any())).thenReturn(
                Optional.empty(), Optional.of(new IntelligenceChatPort.ChatOutcome("  ", 100)));

        service.generateBrief();

        verify(chatPort, times(2)).complete(any(), any());
        ArgumentCaptor<DailyBrief> captor = ArgumentCaptor.forClass(DailyBrief.class);
        verify(briefRepository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(BriefStatus.GENERATED);
        assertThat(captor.getValue().contentMd())
                .doesNotContain(LEAD)
                .contains("- [第1号情报](https://example.com/n1)"); // 条目照常在档
    }

    @Test
    @DisplayName("给定节导语LLM调用抛异常，when生成简报，then单节隔离降级不FAILED")
    void givenLlmCallThrows_whenGenerate_thenStillGeneratedWithoutLead() {
        when(newsRepository.findMajorSince(any(), anyInt())).thenReturn(List.of(news(1, 90, "MACRO")));
        when(chatPort.complete(any(), any())).thenThrow(new IllegalStateException("llm down"));

        service.generateBrief();

        ArgumentCaptor<DailyBrief> captor = ArgumentCaptor.forClass(DailyBrief.class);
        verify(briefRepository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(BriefStatus.GENERATED);
        assertThat(captor.getValue().contentMd()).contains("- [第1号情报](https://example.com/n1)");
    }

    @Test
    @DisplayName("给定窗口内无候选，when生成简报，thenEMPTY_SIMPLE留档今日无重大情报与数据截止")
    void givenNoCandidates_whenGenerate_thenEmptySimpleArchived() {
        when(newsRepository.findMajorSince(any(), anyInt())).thenReturn(List.of());
        when(newsRepository.countExtractedByDate(any())).thenReturn(0L);

        service.generateBrief();

        verify(chatPort, never()).complete(any(), any());
        ArgumentCaptor<DailyBrief> captor = ArgumentCaptor.forClass(DailyBrief.class);
        verify(briefRepository).save(captor.capture());
        DailyBrief brief = captor.getValue();
        assertThat(brief.status()).isEqualTo(BriefStatus.EMPTY_SIMPLE);
        assertThat(brief.contentMd()).contains("今日无重大情报").contains("数据截止").contains("2026-09-29");
        assertThat(brief.topStocks()).isEmpty();
        assertThat(brief.failReason()).isNull();
        // 空简版伴随当日抽取计数诊断（区分「没抽」与「没重大」）
        verify(newsRepository).countExtractedByDate(TODAY);
    }

    @Test
    @DisplayName("给定取数抛异常，when生成简报，thenFAILED留档fail_reason不外抛")
    void givenRepositoryBlowsUp_whenGenerate_thenFailedArchivedWithReason() {
        when(newsRepository.findMajorSince(any(), anyInt()))
                .thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> service.generateBrief()).doesNotThrowAnyException();

        ArgumentCaptor<DailyBrief> captor = ArgumentCaptor.forClass(DailyBrief.class);
        verify(briefRepository).save(captor.capture());
        DailyBrief brief = captor.getValue();
        assertThat(brief.status()).isEqualTo(BriefStatus.FAILED);
        assertThat(brief.failReason()).contains("db down");
        assertThat(brief.contentMd()).isNotBlank(); // content_md NOT NULL：失败同留档
        assertThat(brief.model()).isEqualTo(props.getLlm().getModel());
        verify(chatPort, never()).complete(any(), any());
    }

    @Test
    @DisplayName("给定日历端口抛异常，when调度入口，then顶层吞异常不炸调度线程")
    void givenCalendarBlowsUp_whenScheduled_thenSwallowed() {
        when(tradingCalendar.isTradingDay(any())).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> service.generateBriefScheduled()).doesNotThrowAnyException();
        verify(briefRepository, never()).save(any());
    }

    // ── BriefComposer 边界直测（同包纯函数） ────────────────────────

    @Test
    @DisplayName("给定无url与无方向条目，when拼装，then行格式退化为纯标题并省略方向段")
    void givenItemWithoutUrlOrDirection_whenCompose_thenLineDegradesGracefully() {
        NewsRecord noUrl = new NewsRecord(1L, "eastmoney_724", "ext-1", "无链接情报", "摘要",
                Instant.parse("2026-09-29T00:30:00Z"), null, "[]",
                Instant.parse("2026-09-29T00:40:00Z"),
                "MACRO", List.of(), List.of(), "AI摘要", null, List.of(),
                88, ExtractStatus.SUCCESS, "m", Instant.parse("2026-09-29T00:45:00Z"));
        NewsRecord neutral = new NewsRecord(2L, "eastmoney_724", "ext-2", "第2号情报", "摘要",
                Instant.parse("2026-09-29T00:30:00Z"), "https://example.com/n2", "[]",
                Instant.parse("2026-09-29T00:40:00Z"),
                "MACRO", List.of(), List.of(), "AI摘要", Direction.NEUTRAL, List.of(),
                60, ExtractStatus.SUCCESS, "m", Instant.parse("2026-09-29T00:45:00Z"));

        BriefComposer.Composed composed = BriefComposer.compose(TODAY,
                BriefSelectionPolicy.select(List.of(noUrl, neutral), 80, 50, 2, 25),
                Map.of(), WINDOW_START, CLOCK.instant());

        assertThat(composed.contentMd()).contains("- 无链接情报（重要度 88）"); // 无 url 无方向
        assertThat(composed.contentMd()).contains("- [第2号情报](https://example.com/n2)（重要度 60 · 中性）");
    }

    @Test
    @DisplayName("给定选中条目标的超20个，when拼装，thentop_stocks按频次截断前20")
    void givenMoreThanTwentyStocks_whenCompose_thenTopStocksCappedAt20() {
        List<NewsRecord> candidates = new java.util.ArrayList<>();
        for (int i = 1; i <= 22; i++) {
            candidates.add(news(i, 90, "MACRO", "S%02d".formatted(i))); // S01..S22 各 1 次
        }
        candidates.add(news(99, 90, "MACRO", "S01", "S02")); // S01/S02 频次 +1 → 并列居首

        BriefComposer.Composed composed = BriefComposer.compose(TODAY,
                BriefSelectionPolicy.select(candidates, 80, 50, 15, 25),
                Map.of(), WINDOW_START, CLOCK.instant());

        // 频次降序（S01/S02 各 2 次）+ 同频代码字典序，截断前 20：S21/S22 出局
        assertThat(composed.topStocks()).hasSize(20);
        assertThat(composed.topStocks().subList(0, 2)).containsExactly("S01", "S02");
        assertThat(composed.topStocks()).doesNotContain("S21", "S22");
    }

    // ── fixture 助手 ───────────────────────────────────────────────

    /** SUCCESS 抽取完成的候选条目（importance/eventType/标的可配，标题按 id 唯一）。 */
    private static NewsRecord news(long id, int importance, String eventType, String... stockCodes) {
        String title = "第" + id + "号情报";
        return new NewsRecord(id, "eastmoney_724", "ext-" + id, title, title + "的摘要",
                Instant.parse("2026-09-29T00:30:00Z"), "https://example.com/n" + id, "[]",
                Instant.parse("2026-09-29T00:40:00Z"),
                eventType, List.of(stockCodes), List.of(), title + "的AI摘要", Direction.BULLISH,
                List.of(), importance, ExtractStatus.SUCCESS, "deepseek-v4-flash",
                Instant.parse("2026-09-29T00:45:00Z"));
    }
}
