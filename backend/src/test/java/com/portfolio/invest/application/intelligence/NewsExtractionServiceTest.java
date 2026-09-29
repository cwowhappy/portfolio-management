package com.portfolio.invest.application.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.invest.application.alert.AlertNotifier;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.intelligence.Direction;
import com.portfolio.invest.domain.intelligence.ExtractStatus;
import com.portfolio.invest.domain.intelligence.NewsExtractResult;
import com.portfolio.invest.domain.intelligence.NewsRecord;
import com.portfolio.invest.domain.intelligence.NewsRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 新闻抽取批单元切片（mock 仓库/LLM 端口/token 护栏/告警；NewsExtractor 用真实纯组件）：
 * 「批量→逐条→失败隔离」骨架（D6）：解析失败重试 1 次仍败标 FAILED 不阻塞批次、
 * LLM 未配置整批静默跳过留 PENDING、token 护栏当日停批 + 告警恰一次（D16）、
 * importance 落库前夹紧 0..100、空批快退、多批循环、单条异常隔离、调度顶层吞异常。
 */
class NewsExtractionServiceTest {

    /** 上海 2026-09-29 09:00（UTC 01:00）——当日口径锚点。 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-29T01:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 29);

    private static final String OK_JSON = """
            {"event_type":"EARNINGS","stock_codes":["600519"],"industry_codes":["801250"],
            "summary":"业绩超预期","direction":"BULLISH","key_numbers":["营收 12.34 亿元"],"importance":85}""";
    private static final String INVALID_JSON = "抱歉我无法以 JSON 输出{{{";

    private final NewsRepository newsRepository = mock(NewsRepository.class);
    private final IntelligenceChatPort chatPort = mock(IntelligenceChatPort.class);
    private final IntelligenceTokenBudget tokenBudget = mock(IntelligenceTokenBudget.class);
    private final AlertNotifier alertNotifier = mock(AlertNotifier.class);
    private final InvestProperties props = new InvestProperties();
    private NewsExtractionService service;

    @BeforeEach
    void setUp() {
        service = new NewsExtractionService(newsRepository, chatPort, tokenBudget,
                alertNotifier, props, new NewsExtractor(), CLOCK);
        when(tokenBudget.exhausted()).thenReturn(false);
        when(tokenBudget.tryAcquire(anyLong())).thenReturn(true);
        when(alertNotifier.send(any(), any(), anyList())).thenReturn(true);
    }

    @Test
    @DisplayName("给定3条待抽取其中第1条两次非法JSON，when抽取批，then重试1次后1条FAILED+2条SUCCESS且互不阻塞")
    void givenThreeNewsFirstAlwaysInvalid_whenExtractPending_thenOneFailedTwoSuccessWithRetry() {
        givenPending(news(1L, "坏消息"), news(2L, "好消息甲"), news(3L, "好消息乙"));
        // 第 1 条两次非法 JSON（首抽 + 重试均败），第 2、3 条正常
        when(chatPort.complete(any(), any())).thenReturn(
                Optional.of(outcome(INVALID_JSON)), Optional.of(outcome(INVALID_JSON)),
                Optional.of(outcome(OK_JSON)), Optional.of(outcome(OK_JSON)));

        service.extractPending();

        verify(chatPort, times(4)).complete(any(), any()); // 3 条 + 1 次重试
        ArgumentCaptor<NewsExtractResult> captor = ArgumentCaptor.forClass(NewsExtractResult.class);
        verify(newsRepository, times(3)).upsertExtract(anyLong(), captor.capture());
        assertThat(captor.getAllValues()).extracting(NewsExtractResult::status)
                .containsExactly(ExtractStatus.FAILED, ExtractStatus.SUCCESS, ExtractStatus.SUCCESS);

        NewsExtractResult failed = captor.getAllValues().getFirst();
        assertThat(failed.eventType()).isNull();
        assertThat(failed.importance()).isNull();
        assertThat(failed.model()).isEqualTo(props.getLlm().getModel());
        assertThat(failed.extractedAt()).isEqualTo(CLOCK.instant());

        NewsExtractResult success = captor.getAllValues().get(1);
        assertThat(success.eventType()).isEqualTo("EARNINGS");
        assertThat(success.summary()).isEqualTo("业绩超预期");
        assertThat(success.direction()).isEqualTo(Direction.BULLISH);
        assertThat(success.stockCodes()).containsExactly("600519");
        assertThat(success.industryCodes()).containsExactly("801250");
        assertThat(success.keyNumbers()).containsExactly("营收 12.34 亿元");
        assertThat(success.importance()).isEqualTo(85);
        assertThat(success.model()).isEqualTo(props.getLlm().getModel());
    }

    @Test
    @DisplayName("给定首抽非法JSON重试合法，when抽取批，then重试救回标SUCCESS（调用2次）")
    void givenFirstAttemptInvalidSecondValid_whenExtractPending_thenRescuedByRetry() {
        givenPending(news(1L, "先错后对"));
        when(chatPort.complete(any(), any())).thenReturn(
                Optional.of(outcome(INVALID_JSON)), Optional.of(outcome(OK_JSON)));

        service.extractPending();

        verify(chatPort, times(2)).complete(any(), any());
        ArgumentCaptor<NewsExtractResult> captor = ArgumentCaptor.forClass(NewsExtractResult.class);
        verify(newsRepository).upsertExtract(anyLong(), captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(ExtractStatus.SUCCESS);
    }

    @Test
    @DisplayName("给定LLM通道未配置，when抽取批，then首批即静默跳批不标FAILED不抛异常")
    void givenLlmUnconfigured_whenExtractPending_thenSkipSilentlyLeavePending() {
        givenPending(news(1L, "甲"), news(2L, "乙"), news(3L, "丙"));
        when(chatPort.complete(any(), any())).thenReturn(Optional.empty());

        assertThatCode(() -> service.extractPending()).doesNotThrowAnyException();

        // 0 次状态置换（条目留 PENDING 下批再试），且首条即跳批不再调后续条目
        verify(newsRepository, never()).upsertExtract(anyLong(), any());
        verify(chatPort, times(1)).complete(any(), any());
    }

    @Test
    @DisplayName("给定LLM批中途失效，when抽取批，then已完成条目落库其余留PENDING整批中止")
    void givenLlmDiesMidBatch_whenExtractPending_thenProcessedPersistedRestPending() {
        givenPending(news(1L, "甲"), news(2L, "乙"), news(3L, "丙"));
        when(chatPort.complete(any(), any())).thenReturn(
                Optional.of(outcome(OK_JSON)), Optional.empty());

        service.extractPending();

        ArgumentCaptor<Long> ids = ArgumentCaptor.forClass(Long.class);
        verify(newsRepository, times(1)).upsertExtract(ids.capture(), any());
        assertThat(ids.getValue()).isEqualTo(1L); // 仅第 1 条置换，2/3 留 PENDING
        verify(chatPort, times(2)).complete(any(), any());
    }

    @Test
    @DisplayName("给定当日token累计超护栏，when抽取批，then当前条落库后停批剩余不动且告警恰1次")
    void givenGuardrailExceededMidBatch_whenExtractPending_thenStopWithSingleAlert() {
        givenPending(news(1L, "甲"), news(2L, "乙"), news(3L, "丙"));
        when(chatPort.complete(any(), any())).thenReturn(Optional.of(outcome(OK_JSON)));
        when(tokenBudget.tryAcquire(anyLong())).thenReturn(false); // 第 1 条计入即超限

        service.extractPending();

        // 第 1 条已完成抽取照常落库（LLM 已消费），第 2、3 条不动（无 LLM 调用、无置换）
        ArgumentCaptor<Long> ids = ArgumentCaptor.forClass(Long.class);
        verify(newsRepository, times(1)).upsertExtract(ids.capture(), any());
        assertThat(ids.getValue()).isEqualTo(1L);
        verify(chatPort, times(1)).complete(any(), any());
        verify(alertNotifier, times(1)).send(any(), any(), anyList());
    }

    @Test
    @DisplayName("给定护栏晨间已超限，when晚批再入，then不取数不调LLM且当日不重复告警")
    void givenGuardrailAlreadyExhausted_whenExtractPendingAgain_thenFastExitNoDuplicateAlert() {
        when(tokenBudget.exhausted()).thenReturn(true);

        service.extractPending();
        service.extractPending();

        verify(newsRepository, never()).findPendingForExtraction(any(), anyInt(), anyInt());
        verify(chatPort, never()).complete(any(), any());
        verify(alertNotifier, times(1)).send(any(), any(), anyList()); // 当日告警恰 1 次
    }

    @Test
    @DisplayName("给定当日无待抽取新闻，when抽取批，then空批快退零LLM调用")
    void givenNoPendingNews_whenExtractPending_thenFastReturn() {
        when(newsRepository.findPendingForExtraction(any(), anyInt(), anyInt())).thenReturn(List.of());

        service.extractPending();

        // 游标窗口经服务常量传导：3 个自然日含当日（积压跨日续抽）
        verify(newsRepository, times(1)).findPendingForExtraction(eq(TODAY), eq(3), anyInt());
        verify(chatPort, never()).complete(any(), any());
        verify(newsRepository, never()).upsertExtract(anyLong(), any());
    }

    @Test
    @DisplayName("给定待抽取超过单批容量，when抽取批，then按配置批大小循环取批直至清空")
    void givenMorePendingThanBatchSize_whenExtractPending_thenLoopUntilDrained() {
        props.getIntelligence().setExtractBatchSize(2);
        when(newsRepository.findPendingForExtraction(any(), anyInt(), anyInt())).thenReturn(
                List.of(news(1L, "甲"), news(2L, "乙")),
                List.of(news(1L, "甲"), news(2L, "乙"), news(3L, "丙")), // 仓库侧仍返回全量
                List.of());
        when(chatPort.complete(any(), any())).thenReturn(Optional.of(outcome(OK_JSON)));

        service.extractPending();

        // 批大小经配置传导（D6：15 条/批默认，此处 2）；已处理条目同轮不重复抽取
        verify(newsRepository, times(3)).findPendingForExtraction(any(), eq(3), eq(2));
        verify(newsRepository, times(3)).upsertExtract(anyLong(), any());
        verify(chatPort, times(3)).complete(any(), any());
    }

    @Test
    @DisplayName("给定LLM输出importance=150，when抽取批，then落库前夹紧为100")
    void givenImportanceAboveRange_whenExtractPending_thenClampedTo100() {
        givenPending(news(1L, "极端重要"));
        when(chatPort.complete(any(), any())).thenReturn(Optional.of(outcome(OK_JSON.replace("85", "150"))));

        service.extractPending();

        ArgumentCaptor<NewsExtractResult> captor = ArgumentCaptor.forClass(NewsExtractResult.class);
        verify(newsRepository).upsertExtract(anyLong(), captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(ExtractStatus.SUCCESS);
        assertThat(captor.getValue().importance()).isEqualTo(100);
    }

    @Test
    @DisplayName("给定首条落库抛异常，when抽取批，then单条隔离继续其余条目")
    void givenUpsertBlowsUpOnFirst_whenExtractPending_thenOthersStillProcessed() {
        givenPending(news(1L, "甲"), news(2L, "乙"), news(3L, "丙"));
        when(chatPort.complete(any(), any())).thenReturn(Optional.of(outcome(OK_JSON)));
        doThrow(new IllegalStateException("db down")).doNothing().doNothing()
                .when(newsRepository).upsertExtract(anyLong(), any());

        assertThatCode(() -> service.extractPending()).doesNotThrowAnyException();

        verify(chatPort, times(3)).complete(any(), any()); // 异常条目不拖垮其余
        verify(newsRepository, times(3)).upsertExtract(anyLong(), any()); // 3 次尝试（1 失败 2 成功）
    }

    @Test
    @DisplayName("给定取数抛异常，when调度入口，then顶层吞异常不炸调度线程")
    void givenRepositoryBlowsUp_whenScheduled_thenSwallowed() {
        when(newsRepository.findPendingForExtraction(any(), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> service.extractPendingScheduled()).doesNotThrowAnyException();
        verify(chatPort, never()).complete(any(), any());
    }

    @Test
    @DisplayName("给定3条中1条解析失败，when抽取批，then批末告警恰一次且文案含条数")
    void givenOneParseFailedAmongThree_whenExtractPending_thenAlertOnceWithCount() {
        givenPending(news(1L, "坏消息"), news(2L, "好消息甲"), news(3L, "好消息乙"));
        when(chatPort.complete(any(), any())).thenReturn(
                Optional.of(outcome(INVALID_JSON)), Optional.of(outcome(INVALID_JSON)),
                Optional.of(outcome(OK_JSON)), Optional.of(outcome(OK_JSON)));

        service.extractPending();

        // NFR-5：批末统计本轮 PARSE_FAILED 置换数——1 条 FAILED 告警恰一次，文案含条数
        verify(alertNotifier, times(1)).send(any(), eq("red"), argThat(lines ->
                lines != null && lines.stream().anyMatch(line -> line.contains("1 条"))));
    }

    @Test
    @DisplayName("给定全部抽取成功，when抽取批，then不发送解析失败告警")
    void givenAllParseSuccess_whenExtractPending_thenNoFailureAlert() {
        givenPending(news(1L, "消息甲"), news(2L, "消息乙"));
        when(chatPort.complete(any(), any())).thenReturn(Optional.of(outcome(OK_JSON)));

        service.extractPending();

        verify(newsRepository, times(2)).upsertExtract(anyLong(), any());
        verify(alertNotifier, never()).send(any(), any(), anyList());
    }

    @Test
    @DisplayName("给定合计7字符的超短条目与正常条目，when抽取批，then短条目不送LLM不置换状态留PENDING")
    void givenShortTextNews_whenExtractPending_thenSkippedWithoutLlmOrUpsert() {
        NewsRecord shortNews = newsWithText(1L, "超短快讯标题", "短"); // 6+1=7 字符 < 8（D7）
        givenPending(shortNews, news(2L, "正常新闻"));
        when(chatPort.complete(any(), any())).thenReturn(Optional.of(outcome(OK_JSON)));

        service.extractPending();

        // D7：短条目不送 LLM（仅正常条目 1 次调用）、不置换状态（仅正常条目 1 次落库）
        verify(chatPort, times(1)).complete(any(), any());
        ArgumentCaptor<Long> ids = ArgumentCaptor.forClass(Long.class);
        verify(newsRepository, times(1)).upsertExtract(ids.capture(), any());
        assertThat(ids.getValue()).isEqualTo(2L);
    }

    // ── fixture 助手 ───────────────────────────────────────────────

    private void givenPending(NewsRecord... news) {
        when(newsRepository.findPendingForExtraction(any(), anyInt(), anyInt()))
                .thenReturn(List.of(news)).thenReturn(List.of());
    }

    private static IntelligenceChatPort.ChatOutcome outcome(String text) {
        return new IntelligenceChatPort.ChatOutcome(text, 100);
    }

    /**
     * 无抽取行的当日 PENDING raw（raw 侧字段齐备、抽取侧全 null）。摘要为固定长文本——
     * 单字标题（如「甲」）拼缀式摘要会撞 D7 的 8 字符阈值被误跳过。
     */
    private static NewsRecord news(long id, String title) {
        return newsWithText(id, title, "这条新闻的摘要内容足够长");
    }

    /** 自定 title/summary 的无抽取行 PENDING raw（D7 长度过滤测试用）。 */
    private static NewsRecord newsWithText(long id, String title, String summary) {
        return new NewsRecord(id, "eastmoney_724", "ext-" + id, title, summary,
                Instant.parse("2026-09-29T00:30:00Z"), null, "[]",
                Instant.parse("2026-09-29T00:40:00Z"),
                null, null, null, null, null, null, null, null, null, null);
    }
}
