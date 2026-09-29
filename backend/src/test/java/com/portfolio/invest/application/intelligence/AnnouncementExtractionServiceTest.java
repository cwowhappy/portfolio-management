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
import com.portfolio.invest.domain.intelligence.AnnouncementExtractResult;
import com.portfolio.invest.domain.intelligence.AnnouncementRecord;
import com.portfolio.invest.domain.intelligence.AnnouncementRepository;
import com.portfolio.invest.domain.intelligence.AnnouncementType;
import com.portfolio.invest.domain.intelligence.ExtractStatus;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 公告抽取批单元切片（mock 仓库/LLM 端口/PDF 端口/下载器/护栏/告警/触发器；Extractor 用
 * 真实纯组件）：「批量→逐条→失败隔离」骨架（照 NewsExtractionService）+ 公告域差异——
 * 业绩类预筛（标题/栏目直判）才送 LLM、非业绩类 SUCCESS 空抽取落库、PDF 下载/无文本层
 * FAILED、无 pdf_url 凭标题降级、批末 AnnouncementPushTrigger.pushExtracted(批起点)、
 * token 护栏共享停批（D16）、双 cron 调度入口（22:40/06:40 MON-FRI）。
 */
class AnnouncementExtractionServiceTest {

    /** 上海 2026-09-29 22:45（UTC 14:45）——当日口径锚点（晚间批时刻）。 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-29T14:45:00Z"), ZoneId.of("Asia/Shanghai"));
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 29);

    private static final String OK_JSON = """
            {"metrics":{"revenueYi":128.56,"netProfitYi":31.2,"netProfitYoyPct":25.3,
            "deductedProfitYi":30.05,"grossMarginPct":null,"dividendDesc":"每10股派2元",
            "undisclosed":["毛利率"]},"annTypes":["PERIODIC_REPORT","EQUITY_INCENTIVE"]}""";
    private static final String PDF_TEXT = "贵州茅台2026年半年度报告：营业收入128.56亿元，归母净利润31.2亿元，同比增加25.3%。";

    private final AnnouncementRepository announcementRepository = mock(AnnouncementRepository.class);
    private final IntelligenceChatPort chatPort = mock(IntelligenceChatPort.class);
    private final AnnouncementPdfTextPort pdfTextPort = mock(AnnouncementPdfTextPort.class);
    private final PdfFetcher pdfFetcher = mock(PdfFetcher.class);
    private final IntelligenceTokenBudget tokenBudget = mock(IntelligenceTokenBudget.class);
    private final AlertNotifier alertNotifier = mock(AlertNotifier.class);
    private final InvestProperties props = new InvestProperties();
    private final ObjectProvider<AnnouncementPushTrigger> triggerProvider = mockTriggerProvider();
    private final AnnouncementPushTrigger pushTrigger = mock(AnnouncementPushTrigger.class);
    private AnnouncementExtractionService service;

    @BeforeEach
    void setUp() {
        when(triggerProvider.getIfAvailable()).thenReturn(pushTrigger);
        service = new AnnouncementExtractionService(announcementRepository, chatPort, pdfTextPort,
                pdfFetcher, tokenBudget, alertNotifier, props, triggerProvider,
                new AnnouncementExtractor(), CLOCK);
        when(tokenBudget.exhausted()).thenReturn(false);
        when(tokenBudget.tryAcquire(anyLong())).thenReturn(true);
        when(alertNotifier.send(any(), any(), anyList())).thenReturn(true);
    }

    @Test
    @DisplayName("给定业绩类公告（标题命中），when抽取批，then下载→PDF文本→LLM→SUCCESS六字段并集落库")
    void givenPerformanceTitle_whenExtractPending_thenFullChainSuccess() throws Exception {
        givenPending(announcement(1L, "贵州茅台2026年半年度报告", "半年度报告摘要", "https://static.cninfo.com.cn/finalpage/a.pdf", true));
        when(pdfFetcher.download(any())).thenReturn("fake-pdf".getBytes(StandardCharsets.UTF_8));
        when(pdfTextPort.extract(any(byte[].class))).thenReturn(PDF_TEXT);
        when(chatPort.complete(any(), any())).thenReturn(Optional.of(outcome(OK_JSON)));

        service.extractPending();

        verify(pdfFetcher).download("https://static.cninfo.com.cn/finalpage/a.pdf");
        verify(pdfTextPort).extract(any(byte[].class));
        ArgumentCaptor<AnnouncementExtractResult> captor = ArgumentCaptor.forClass(AnnouncementExtractResult.class);
        verify(announcementRepository).upsertExtract(eq(1L), captor.capture());
        AnnouncementExtractResult result = captor.getValue();
        assertThat(result.status()).isEqualTo(ExtractStatus.SUCCESS);
        assertThat(result.metrics().revenueYi()).isEqualByComparingTo("128.56");
        assertThat(result.metrics().undisclosed()).containsExactly("毛利率");
        // 并集：栏目直判 PERIODIC_REPORT（半年度报告摘要）在前 + LLM 增补 EQUITY_INCENTIVE
        assertThat(result.annTypes()).containsExactly(AnnouncementType.PERIODIC_REPORT, AnnouncementType.EQUITY_INCENTIVE);
        assertThat(result.pdfText()).isEqualTo(PDF_TEXT);
        assertThat(result.model()).isEqualTo(props.getLlm().getModel());
        assertThat(result.extractedAt()).isEqualTo(CLOCK.instant());
        verify(tokenBudget).tryAcquire(100L);
    }

    @Test
    @DisplayName("给定标题无业绩词但栏目直判命中，when抽取批，then仍送LLM且sourceTypes进并集")
    void givenColumnDirectTypeOnly_whenExtractPending_thenExtractedWithSourceType() throws Exception {
        givenPending(announcement(1L, "向特定对象发行A股股票预案", "增发预案||董事会决议", "https://pdf.dfcfw.com/pdf/H2_x_1.pdf", true));
        when(pdfFetcher.download(any())).thenReturn("pdf".getBytes(StandardCharsets.UTF_8));
        when(pdfTextPort.extract(any(byte[].class))).thenReturn("发行方案正文".repeat(20));
        when(chatPort.complete(any(), any())).thenReturn(Optional.of(outcome("{\"metrics\":{},\"annTypes\":[]}")));

        service.extractPending();

        ArgumentCaptor<AnnouncementExtractResult> captor = ArgumentCaptor.forClass(AnnouncementExtractResult.class);
        verify(announcementRepository).upsertExtract(anyLong(), captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(ExtractStatus.SUCCESS);
        // 栏目「增发」直判 PLACEMENT（LLM 未输出类型）→ 并集仅含直判
        assertThat(captor.getValue().annTypes()).containsExactly(AnnouncementType.PLACEMENT);
        verify(chatPort, times(1)).complete(any(), any());
    }

    @Test
    @DisplayName("给定非业绩类公告，when抽取批，thenSUCCESS空抽取落库不下载不调LLM")
    void givenNonPerformance_whenExtractPending_thenSuccessEmptyWithoutLlmOrDownload() throws Exception {
        givenPending(announcement(1L, "关于召开2026年第三次临时股东会的通知", "召开股东大会通知", "https://static.cninfo.com.cn/finalpage/b.pdf", false));

        service.extractPending();

        ArgumentCaptor<AnnouncementExtractResult> captor = ArgumentCaptor.forClass(AnnouncementExtractResult.class);
        verify(announcementRepository).upsertExtract(eq(1L), captor.capture());
        AnnouncementExtractResult result = captor.getValue();
        assertThat(result.status()).isEqualTo(ExtractStatus.SUCCESS);
        assertThat(result.metrics()).isNull();
        assertThat(result.annTypes()).isEmpty();
        assertThat(result.pdfText()).isNull();
        assertThat(result.model()).isNull(); // 未送 LLM：model 留空为「未经模型抽取」标识
        assertThat(result.extractedAt()).isEqualTo(CLOCK.instant());
        verify(pdfFetcher, never()).download(any());
        verify(pdfTextPort, never()).extract(any(byte[].class));
        verify(chatPort, never()).complete(any(), any());
    }

    @Test
    @DisplayName("给定PDF下载失败，when抽取批，then本条FAILED其余继续且批末告警含条数")
    void givenPdfDownloadFailure_whenExtractPending_thenFailedWithAlert() throws Exception {
        givenPending(announcement(1L, "坏链公告2026年年度报告", null, "https://static.cninfo.com.cn/finalpage/gone.pdf", true),
                announcement(2L, "贵州茅台2026年半年度报告", null, "https://static.cninfo.com.cn/finalpage/ok.pdf", true));
        when(pdfFetcher.download(any()))
                .thenThrow(new PdfFetcher.PdfFetchException("下载失败: HTTP 404", new IllegalStateException("404")))
                .thenReturn("pdf".getBytes(StandardCharsets.UTF_8));
        when(pdfTextPort.extract(any(byte[].class))).thenReturn(PDF_TEXT);
        when(chatPort.complete(any(), any())).thenReturn(Optional.of(outcome(OK_JSON)));

        service.extractPending();

        ArgumentCaptor<AnnouncementExtractResult> captor = ArgumentCaptor.forClass(AnnouncementExtractResult.class);
        verify(announcementRepository, times(2)).upsertExtract(anyLong(), captor.capture());
        assertThat(captor.getAllValues()).extracting(AnnouncementExtractResult::status)
                .containsExactly(ExtractStatus.FAILED, ExtractStatus.SUCCESS);
        assertThat(captor.getAllValues().getFirst().metrics()).isNull();
        assertThat(captor.getAllValues().getFirst().pdfText()).isNull();
        // 坏链条不消耗 LLM，好链正常抽取
        verify(chatPort, times(1)).complete(any(), any());
        // 批末 FAILED 告警恰一次，文案含条数
        verify(alertNotifier, times(1)).send(any(), eq("red"), argThat(lines ->
                lines != null && lines.stream().anyMatch(line -> line.contains("1 条"))));
    }

    @Test
    @DisplayName("给定PDF解析异常或无文本层，when抽取批，then均标FAILED")
    void givenPdfPortFailureOrNoTextLayer_whenExtractPending_thenFailed() throws Exception {
        givenPending(announcement(1L, "加密公告2026年半年度报告", null, "https://x/enc.pdf", true),
                announcement(2L, "扫描件公告2026年半年度报告", null, "https://x/scan.pdf", true));
        when(pdfFetcher.download(any())).thenReturn("pdf".getBytes(StandardCharsets.UTF_8));
        when(pdfTextPort.extract(any(byte[].class)))
                .thenThrow(new AnnouncementPdfTextPortException("加密或损坏"))
                .thenReturn(""); // 无文本层（扫描件）：空串契约

        service.extractPending();

        ArgumentCaptor<AnnouncementExtractResult> captor = ArgumentCaptor.forClass(AnnouncementExtractResult.class);
        verify(announcementRepository, times(2)).upsertExtract(anyLong(), captor.capture());
        assertThat(captor.getAllValues()).extracting(AnnouncementExtractResult::status)
                .containsExactly(ExtractStatus.FAILED, ExtractStatus.FAILED);
        verify(chatPort, never()).complete(any(), any()); // 两条均未到 LLM 阶段
    }

    @Test
    @DisplayName("给定无pdf_url的业绩类公告，when抽取批，then跳过下载凭标题送LLM")
    void givenNoPdfUrl_whenExtractPending_thenTitleOnlyExtraction() throws Exception {
        givenPending(announcement(1L, "某公司2026年年度业绩预告", null, null, true));
        when(chatPort.complete(any(), any())).thenReturn(Optional.of(outcome(OK_JSON)));

        service.extractPending();

        verify(pdfFetcher, never()).download(any());
        verify(chatPort, times(1)).complete(any(),
                argThat(user -> user != null && user.contains("无PDF正文")));
        ArgumentCaptor<AnnouncementExtractResult> captor = ArgumentCaptor.forClass(AnnouncementExtractResult.class);
        verify(announcementRepository).upsertExtract(anyLong(), captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(ExtractStatus.SUCCESS);
        assertThat(captor.getValue().pdfText()).isEmpty(); // 实际送入 LLM 的空文本原样留痕
    }

    @Test
    @DisplayName("给定LLM通道未配置，when抽取批，then首批即静默跳批不标FAILED不抛异常")
    void givenLlmUnconfigured_whenExtractPending_thenSkipSilentlyLeavePending() throws Exception {
        givenPending(announcement(1L, "甲公司2026年半年度报告", null, "https://x/1.pdf", true),
                announcement(2L, "乙公司2026年半年度报告", null, "https://x/2.pdf", true));
        when(pdfFetcher.download(any())).thenReturn("pdf".getBytes(StandardCharsets.UTF_8));
        when(pdfTextPort.extract(any(byte[].class))).thenReturn(PDF_TEXT);
        when(chatPort.complete(any(), any())).thenReturn(Optional.empty());

        assertThatCode(() -> service.extractPending()).doesNotThrowAnyException();

        verify(announcementRepository, never()).upsertExtract(anyLong(), any());
        verify(chatPort, times(1)).complete(any(), any()); // 首条即跳批
    }

    @Test
    @DisplayName("给定LLM批中途失效，when抽取批，then已完成条目落库其余留PENDING整批中止")
    void givenLlmDiesMidBatch_whenExtractPending_thenProcessedPersistedRestPending() throws Exception {
        givenPending(announcement(1L, "甲公司2026年半年度报告", null, "https://x/1.pdf", true),
                announcement(2L, "乙公司2026年半年度报告", null, "https://x/2.pdf", true));
        when(pdfFetcher.download(any())).thenReturn("pdf".getBytes(StandardCharsets.UTF_8));
        when(pdfTextPort.extract(any(byte[].class))).thenReturn(PDF_TEXT);
        when(chatPort.complete(any(), any())).thenReturn(
                Optional.of(outcome(OK_JSON)), Optional.empty());

        service.extractPending();

        ArgumentCaptor<Long> ids = ArgumentCaptor.forClass(Long.class);
        verify(announcementRepository, times(1)).upsertExtract(ids.capture(), any());
        assertThat(ids.getValue()).isEqualTo(1L); // 仅第 1 条置换，第 2 条留 PENDING
    }

    @Test
    @DisplayName("给定当日token累计超护栏，when抽取批，then当前条落库后停批剩余不动且告警恰1次")
    void givenGuardrailExceededMidBatch_whenExtractPending_thenStopWithSingleAlert() throws Exception {
        givenPending(announcement(1L, "甲公司2026年半年度报告", null, "https://x/1.pdf", true),
                announcement(2L, "乙公司2026年半年度报告", null, "https://x/2.pdf", true));
        when(pdfFetcher.download(any())).thenReturn("pdf".getBytes(StandardCharsets.UTF_8));
        when(pdfTextPort.extract(any(byte[].class))).thenReturn(PDF_TEXT);
        when(chatPort.complete(any(), any())).thenReturn(Optional.of(outcome(OK_JSON)));
        when(tokenBudget.tryAcquire(anyLong())).thenReturn(false); // 第 1 条计入即超限

        service.extractPending();

        ArgumentCaptor<Long> ids = ArgumentCaptor.forClass(Long.class);
        verify(announcementRepository, times(1)).upsertExtract(ids.capture(), any());
        assertThat(ids.getValue()).isEqualTo(1L); // 第 1 条已落库（LLM 已消费）
        verify(chatPort, times(1)).complete(any(), any());
        verify(alertNotifier, times(1)).send(any(), any(), anyList());
    }

    @Test
    @DisplayName("给定护栏晨间已超限，when晚批再入，then不取数不调LLM且当日不重复告警")
    void givenGuardrailAlreadyExhausted_whenExtractPendingAgain_thenFastExitNoDuplicateAlert() {
        when(tokenBudget.exhausted()).thenReturn(true);

        service.extractPending();
        service.extractPending();

        verify(announcementRepository, never()).findPendingForExtraction(any(), anyInt(), anyInt());
        verify(chatPort, never()).complete(any(), any());
        verify(alertNotifier, times(1)).send(any(), any(), anyList()); // 当日告警恰 1 次
    }

    @Test
    @DisplayName("给定抽取批完成，when批末，thenTrigger以批起点Instant触发定向推送")
    void givenBatchCompleted_whenExtractPending_thenPushTriggerCalledWithBatchStart() throws Exception {
        Instant before = CLOCK.instant();
        givenPending(announcement(1L, "甲公司2026年半年度报告", null, "https://x/1.pdf", true));
        when(pdfFetcher.download(any())).thenReturn("pdf".getBytes(StandardCharsets.UTF_8));
        when(pdfTextPort.extract(any(byte[].class))).thenReturn(PDF_TEXT);
        when(chatPort.complete(any(), any())).thenReturn(Optional.of(outcome(OK_JSON)));

        service.extractPending();

        // 批起点 = extractPending 入口时刻（固定时钟下与 extractedAt 同点；findExtractedMajorSince ≥ 含边界）
        verify(pushTrigger).pushExtracted(before);
    }

    @Test
    @DisplayName("给定Trigger抛异常，when批末触发，then吞异常不拖垮抽取批与告警")
    void givenTriggerBlowsUp_whenExtractPending_thenSwallowed() throws Exception {
        givenPending(announcement(1L, "坏消息公告2026年半年度报告", null, "https://x/1.pdf", true),
                announcement(2L, "好消息公告2026年半年度报告", null, "https://x/2.pdf", true));
        when(pdfFetcher.download(any())).thenReturn("pdf".getBytes(StandardCharsets.UTF_8));
        when(pdfTextPort.extract(any(byte[].class))).thenReturn(PDF_TEXT);
        when(chatPort.complete(any(), any()))
                .thenReturn(Optional.of(outcome("不是JSON{{{")), Optional.of(outcome("不是JSON{{{")))
                .thenReturn(Optional.of(outcome(OK_JSON)));
        doThrow(new IllegalStateException("push down")).when(pushTrigger).pushExtracted(any());

        assertThatCode(() -> service.extractPending()).doesNotThrowAnyException();

        verify(announcementRepository, times(2)).upsertExtract(anyLong(), any());
        verify(alertNotifier, times(1)).send(any(), eq("red"), anyList()); // FAILED 告警不受推送异常影响
    }

    @Test
    @DisplayName("给定Trigger未装配（T7就位前），when批末，then跳过推送不抛异常")
    @SuppressWarnings("unchecked")
    void givenNoTriggerBean_whenExtractPending_thenSkippedQuietly() {
        ObjectProvider<AnnouncementPushTrigger> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);
        AnnouncementExtractionService bare = new AnnouncementExtractionService(announcementRepository,
                chatPort, pdfTextPort, pdfFetcher, tokenBudget, alertNotifier, props, empty,
                new AnnouncementExtractor(), CLOCK);
        when(announcementRepository.findPendingForExtraction(any(), anyInt(), anyInt())).thenReturn(List.of());

        assertThatCode(bare::extractPending).doesNotThrowAnyException();
        verify(announcementRepository, never()).upsertExtract(anyLong(), any());
    }

    @Test
    @DisplayName("给定PDF文本与标题均过短，when抽取批，then跳过不送LLM不落库留PENDING")
    void givenShortText_whenExtractPending_thenSkippedWithoutLlmOrUpsert() throws Exception {
        // 标题 7 字符（< 8）且 PDF 文本 9 字符（< 50）
        AnnouncementRecord shorty = announcement(1L, "短的业绩预告", null, "https://x/1.pdf", true);
        givenPending(shorty, announcement(2L, "正常长度的半年度报告公告", null, "https://x/2.pdf", true));
        when(pdfFetcher.download(any())).thenReturn("pdf".getBytes(StandardCharsets.UTF_8));
        when(pdfTextPort.extract(any(byte[].class))).thenReturn("文本太短", PDF_TEXT);
        when(chatPort.complete(any(), any())).thenReturn(Optional.of(outcome(OK_JSON)));

        service.extractPending();

        // 短条目不送 LLM（仅正常条目 1 次）、不置换状态（仅正常条目 1 次落库）
        verify(chatPort, times(1)).complete(any(), any());
        ArgumentCaptor<Long> ids = ArgumentCaptor.forClass(Long.class);
        verify(announcementRepository, times(1)).upsertExtract(ids.capture(), any());
        assertThat(ids.getValue()).isEqualTo(2L);
    }

    @Test
    @DisplayName("给定PDF文本短但标题足够长，when抽取批，then不跳过照常送LLM")
    void givenShortPdfButLongTitle_whenExtractPending_thenNotSkipped() throws Exception {
        givenPending(announcement(1L, "贵州茅台2026年半年度业绩快报公告", null, "https://x/1.pdf", true));
        when(pdfFetcher.download(any())).thenReturn("pdf".getBytes(StandardCharsets.UTF_8));
        when(pdfTextPort.extract(any(byte[].class))).thenReturn("短文本"); // < 50 但标题 ≥ 8
        when(chatPort.complete(any(), any())).thenReturn(Optional.of(outcome(OK_JSON)));

        service.extractPending();

        verify(chatPort, times(1)).complete(any(), any());
        verify(announcementRepository, times(1)).upsertExtract(anyLong(), any());
    }

    @Test
    @DisplayName("给定单条落库抛异常，when抽取批，then单条隔离继续其余条目")
    void givenUpsertBlowsUpOnFirst_whenExtractPending_thenOthersStillProcessed() throws Exception {
        givenPending(announcement(1L, "甲公司2026年半年度报告", null, "https://x/1.pdf", true),
                announcement(2L, "乙公司2026年半年度报告", null, "https://x/2.pdf", true));
        when(pdfFetcher.download(any())).thenReturn("pdf".getBytes(StandardCharsets.UTF_8));
        when(pdfTextPort.extract(any(byte[].class))).thenReturn(PDF_TEXT);
        when(chatPort.complete(any(), any())).thenReturn(Optional.of(outcome(OK_JSON)));
        doThrow(new IllegalStateException("db down")).doNothing()
                .when(announcementRepository).upsertExtract(anyLong(), any());

        assertThatCode(() -> service.extractPending()).doesNotThrowAnyException();

        verify(chatPort, times(2)).complete(any(), any()); // 异常条目不拖垮其余
        verify(announcementRepository, times(2)).upsertExtract(anyLong(), any());
    }

    @Test
    @DisplayName("给定取数抛异常，when两个调度入口，then顶层吞异常不炸调度线程")
    void givenRepositoryBlowsUp_whenScheduled_thenSwallowed() {
        when(announcementRepository.findPendingForExtraction(any(), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> service.extractPendingNight()).doesNotThrowAnyException();
        assertThatCode(() -> service.extractPendingMorning()).doesNotThrowAnyException();
        verify(chatPort, never()).complete(any(), any());
    }

    @Test
    @DisplayName("给定调度装配，when检查注解，then两cron为22:40/06:40周一至周五且zone上海")
    void givenScheduling_whenInspectAnnotations_thenTwoCronsMonToFri() throws Exception {
        Method night = AnnouncementExtractionService.class.getMethod("extractPendingNight");
        Method morning = AnnouncementExtractionService.class.getMethod("extractPendingMorning");
        Scheduled nightCron = night.getAnnotation(Scheduled.class);
        Scheduled morningCron = morning.getAnnotation(Scheduled.class);

        assertThat(nightCron).isNotNull();
        assertThat(nightCron.cron()).isEqualTo("0 40 22 * * MON-FRI");
        assertThat(nightCron.zone()).isEqualTo("Asia/Shanghai");
        assertThat(morningCron).isNotNull();
        assertThat(morningCron.cron()).isEqualTo("0 40 6 * * MON-FRI");
        assertThat(morningCron.zone()).isEqualTo("Asia/Shanghai");
    }

    @Test
    @DisplayName("给定当日无待抽取公告，when抽取批，then空批快退零LLM调用且游标窗口3日传导")
    void givenNoPending_whenExtractPending_thenFastReturn() {
        when(announcementRepository.findPendingForExtraction(any(), anyInt(), anyInt())).thenReturn(List.of());

        service.extractPending();

        verify(announcementRepository, times(1)).findPendingForExtraction(eq(TODAY), eq(3), anyInt());
        verify(chatPort, never()).complete(any(), any());
        verify(announcementRepository, never()).upsertExtract(anyLong(), any());
    }

    @Test
    @DisplayName("给定待抽取超过单批容量，when抽取批，then按配置批大小循环取批直至清空")
    void givenMorePendingThanBatchSize_whenExtractPending_thenLoopUntilDrained() throws Exception {
        props.getIntelligence().setExtractBatchSize(2);
        when(announcementRepository.findPendingForExtraction(any(), anyInt(), anyInt())).thenReturn(
                List.of(announcement(1L, "甲公司2026年半年度报告", null, "https://x/1.pdf", true),
                        announcement(2L, "乙公司2026年半年度报告", null, "https://x/2.pdf", true)),
                List.of(announcement(1L, "甲公司2026年半年度报告", null, "https://x/1.pdf", true),
                        announcement(2L, "乙公司2026年半年度报告", null, "https://x/2.pdf", true),
                        announcement(3L, "丙公司2026年半年度报告", null, "https://x/3.pdf", true)), // 仓库侧仍返回全量
                List.of());
        when(pdfFetcher.download(any())).thenReturn("pdf".getBytes(StandardCharsets.UTF_8));
        when(pdfTextPort.extract(any(byte[].class))).thenReturn(PDF_TEXT);
        when(chatPort.complete(any(), any())).thenReturn(Optional.of(outcome(OK_JSON)));

        service.extractPending();

        verify(announcementRepository, times(3)).findPendingForExtraction(any(), eq(3), eq(2));
        verify(announcementRepository, times(3)).upsertExtract(anyLong(), any());
        verify(chatPort, times(3)).complete(any(), any());
    }

    // ── fixture 助手 ───────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static ObjectProvider<AnnouncementPushTrigger> mockTriggerProvider() {
        return mock(ObjectProvider.class);
    }

    private void givenPending(AnnouncementRecord... announcements) {
        when(announcementRepository.findPendingForExtraction(any(), anyInt(), anyInt()))
                .thenReturn(List.of(announcements)).thenReturn(List.of());
    }

    private static IntelligenceChatPort.ChatOutcome outcome(String text) {
        return new IntelligenceChatPort.ChatOutcome(text, 100);
    }

    /** 无抽取行的当日 PENDING 公告（announcement 侧字段齐备、extract 侧全 null）。 */
    private static AnnouncementRecord announcement(long id, String title, String annTypeSource,
                                                   String pdfUrl, boolean major) {
        return new AnnouncementRecord(id, "cninfo", "ext-" + id, "600519", "贵州茅台", title,
                annTypeSource, major, Instant.parse("2026-09-29T12:00:00Z"), pdfUrl,
                Instant.parse("2026-09-29T12:30:00Z"),
                null, null, null, null, null, null);
    }
}
