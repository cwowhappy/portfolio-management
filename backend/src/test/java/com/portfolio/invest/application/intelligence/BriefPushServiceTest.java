package com.portfolio.invest.application.intelligence;

import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.intelligence.BriefRepository;
import com.portfolio.invest.domain.intelligence.BriefStatus;
import com.portfolio.invest.domain.intelligence.DailyBrief;
import com.portfolio.invest.domain.intelligence.Direction;
import com.portfolio.invest.domain.intelligence.ExtractStatus;
import com.portfolio.invest.domain.intelligence.IntelligenceSubscription;
import com.portfolio.invest.domain.intelligence.NewsRecord;
import com.portfolio.invest.domain.intelligence.NewsRepository;
import com.portfolio.invest.domain.intelligence.PushLog;
import com.portfolio.invest.domain.intelligence.PushLogRepository;
import com.portfolio.invest.domain.intelligence.PushStatus;
import com.portfolio.invest.domain.intelligence.PushType;
import com.portfolio.invest.domain.intelligence.SubscriptionRepository;
import com.portfolio.invest.domain.intelligence.SubscriptionStock;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 盘前简报推送（D11 群推版 + D17 P4 个性化升级）：交易日 08:30 读当日档 → 逐
 * pushEnabled 用户分流（绑定者 open_id 单发：正文=contentMd（超长截断 3000）+ 可选附节
 * 「⭐ 你关注的」= 简报同窗口（昨日 15:00 起）importance≥majorThreshold 的抽取条目按
 * 订阅 stocks∪industries 过滤前 10；未绑定者由群兜底——群版发送条件=存在 pushEnabled
 * 且未绑定的用户，全部绑定不发群避免双发）+ push_log 留痕（单发 target=openId 带
 * userId，群推 target=chatId 无归属）。FAILED 档发一行失败提示卡（单发/群同形，不附节）、
 * 无档不发不补陈旧、非交易日/未配置跳过、逐用户 try/catch 隔离（查询异常用户归入群
 * 兜底防失联）、调度顶层吞异常。
 */
class BriefPushServiceTest {

    /** 上海 2026-09-29（周二）09:30（UTC 01:30）。 */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-29T01:30:00Z"), ZoneId.of("Asia/Shanghai"));
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 29);
    /** 简报选取窗口起点：昨日 15:00 上海 = 2026-09-28T07:00:00Z（与生成侧同窗口）。 */
    private static final Instant WINDOW_START = Instant.parse("2026-09-28T07:00:00Z");
    private static final String CONTENT_MD = "# 盘前情报速递\n\n## 宏观与政策\n- 条目一";

    private final BriefRepository briefRepository = mock(BriefRepository.class);
    private final IntelligencePushPort pushPort = mock(IntelligencePushPort.class);
    private final PushLogRepository pushLogRepository = mock(PushLogRepository.class);
    private final TradingCalendarPort tradingCalendar = mock(TradingCalendarPort.class);
    private final SubscriptionRepository subscriptionRepository = mock(SubscriptionRepository.class);
    private final SubscriptionService subscriptionService = mock(SubscriptionService.class);
    private final NewsRepository newsRepository = mock(NewsRepository.class);
    private final InvestProperties props = new InvestProperties();
    private BriefPushService service;

    @BeforeEach
    void setUp() {
        props.getIm().setAppId("cli_x");
        props.getIm().setAppSecret("sec");
        props.getIm().setChatId("oc_intel");
        service = new BriefPushService(briefRepository, pushPort, pushLogRepository,
                tradingCalendar, subscriptionRepository, subscriptionService, newsRepository,
                props, CLOCK);
        when(tradingCalendar.isTradingDay(any())).thenReturn(true);
        when(pushPort.sendToGroup(anyString(), anyString(), anyList())).thenReturn(true);
        when(pushPort.sendToUser(anyString(), anyString(), anyString(), anyList())).thenReturn(true);
        // 缺省场景：一名开启推送但未绑定的用户 → 群兜底路径（保留群推版断言语义）
        when(subscriptionRepository.findUserIdsWithPushEnabled()).thenReturn(List.of(9L));
        when(subscriptionService.findOpenId(anyLong())).thenReturn(Optional.empty());
    }

    /** 模拟 DB 读出档（id 非 null——留痕 ref_id 消费）。 */
    private static DailyBrief archived(Long id, BriefStatus status, String contentMd, String failReason) {
        return new DailyBrief(id, TODAY, contentMd, List.of(), status, failReason, "m",
                CLOCK.instant());
    }

    /** 抽取条目 fixture（importance=85 ≥ majorThreshold 80，窗口内已 SUCCESS）。 */
    private static NewsRecord news(long id, String title, List<String> stockCodes,
                                   List<String> industryCodes) {
        return new NewsRecord(id, "eastmoney", "ext-" + id, title, "raw",
                Instant.parse("2026-09-28T15:30:00Z"), "https://n/" + id, "[]",
                Instant.parse("2026-09-28T15:40:00Z"), "EARNINGS", stockCodes, industryCodes,
                "摘要" + id, Direction.BULLISH, List.of(), 85, ExtractStatus.SUCCESS, "m",
                Instant.parse("2026-09-28T16:00:00Z"));
    }

    // ── 群兜底路径（未绑定 pushEnabled 用户存在 → 群统一版，P1 行为保留）──────────

    @Test
    @DisplayName("给定非交易日，when推送，then不查档不推不留痕")
    void givenNonTradingDay_whenPush_thenSkipped() {
        when(tradingCalendar.isTradingDay(TODAY)).thenReturn(false);

        service.pushBrief();

        verify(briefRepository, never()).findByDate(any());
        verify(pushPort, never()).sendToGroup(anyString(), anyString(), anyList());
        verify(pushLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("给定飞书im未配置，when推送，then跳过不留FAIL痕（配置缺失非推送失败）")
    void givenImUnconfigured_whenPush_thenSkippedWithoutLog() {
        props.getIm().setChatId("");

        service.pushBrief();

        verify(briefRepository, never()).findByDate(any());
        verify(pushPort, never()).sendToGroup(anyString(), anyString(), anyList());
        verify(pushLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("给定当日无档（生成漏跑），when推送，then不发不补陈旧不留痕")
    void givenNoBriefArchived_whenPush_thenSkipped() {
        when(briefRepository.findByDate(TODAY)).thenReturn(Optional.empty());

        service.pushBrief();

        verify(pushPort, never()).sendToGroup(anyString(), anyString(), anyList());
        verify(pushLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("给定受众为空（全员显式关闭，默认开口径下唯一空态），when推送，then不推不留痕且INFO可观测")
    void givenEmptyAudience_whenPush_thenSkippedWithoutSendButLogged() {
        when(briefRepository.findByDate(TODAY)).thenReturn(
                Optional.of(archived(106L, BriefStatus.GENERATED, CONTENT_MD, null)));
        when(subscriptionRepository.findUserIdsWithPushEnabled()).thenReturn(List.of());
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(BriefPushService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            service.pushBrief();

            verify(pushPort, never()).sendToGroup(anyString(), anyString(), anyList());
            verify(pushPort, never()).sendToUser(anyString(), anyString(), anyString(), anyList());
            verify(pushLogRepository, never()).save(any());
            assertThat(appender.list).as("空受众一行 INFO 留观测（全员显式关闭是默认开口径下唯一空态）")
                    .anyMatch(event -> event.getLevel() == ch.qos.logback.classic.Level.INFO
                            && event.getFormattedMessage().contains("无推送受众"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("给定GENERATED档与未绑定推送用户，when推送，then群推卡片且留痕OK全字段")
    void givenGeneratedBrief_whenPush_thenCardSentAndOkLogged() {
        when(briefRepository.findByDate(TODAY)).thenReturn(
                Optional.of(archived(101L, BriefStatus.GENERATED, CONTENT_MD, null)));

        service.pushBrief();

        verify(pushPort).sendToGroup(eq("【盘前简报】" + TODAY), eq("blue"), eq(List.of(CONTENT_MD)));
        ArgumentCaptor<PushLog> captor = ArgumentCaptor.forClass(PushLog.class);
        verify(pushLogRepository).save(captor.capture());
        PushLog log = captor.getValue();
        assertThat(log.userId()).isNull();          // 群推无归属
        assertThat(log.pushType()).isEqualTo(PushType.BRIEF);
        assertThat(log.target()).isEqualTo("oc_intel");
        assertThat(log.refTable()).isEqualTo("intelligence_daily_brief");
        assertThat(log.refId()).isEqualTo(101L);
        assertThat(log.status()).isEqualTo(PushStatus.OK);
        assertThat(log.error()).isNull();
        assertThat(log.sentAt()).isEqualTo(CLOCK.instant());
    }

    @Test
    @DisplayName("给定EMPTY_SIMPLE档与未绑定推送用户，when推送，then空简版照发照留痕")
    void givenEmptySimpleBrief_whenPush_thenStillSent() {
        String emptyMd = "# 盘前情报速递\n\n今日无重大情报。";
        when(briefRepository.findByDate(TODAY)).thenReturn(
                Optional.of(archived(102L, BriefStatus.EMPTY_SIMPLE, emptyMd, null)));

        service.pushBrief();

        verify(pushPort).sendToGroup(eq("【盘前简报】" + TODAY), eq("blue"), eq(List.of(emptyMd)));
        verify(pushLogRepository).save(any());
    }

    @Test
    @DisplayName("给定FAILED档与未绑定推送用户，when推送，then发一行生成失败提示卡并留痕")
    void givenFailedBrief_whenPush_thenFailureNoticeCard() {
        when(briefRepository.findByDate(TODAY)).thenReturn(
                Optional.of(archived(103L, BriefStatus.FAILED, "# 占位", "db down")));

        service.pushBrief();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> linesCaptor = ArgumentCaptor.forClass(List.class);
        verify(pushPort).sendToGroup(eq("【盘前简报】" + TODAY), eq("red"), linesCaptor.capture());
        String line = String.join("\n", linesCaptor.getValue());
        assertThat(line).contains("今日简报生成失败").contains("db down"); // 运维感知一行卡
        verify(pushLogRepository).save(any());
    }

    @Test
    @DisplayName("给定飞书群推返回失败，when推送，then留痕FAIL不抛")
    void givenPushFails_whenPush_thenFailLoggedWithoutThrowing() {
        when(briefRepository.findByDate(TODAY)).thenReturn(
                Optional.of(archived(104L, BriefStatus.GENERATED, CONTENT_MD, null)));
        when(pushPort.sendToGroup(anyString(), anyString(), anyList())).thenReturn(false);

        assertThatCode(() -> service.pushBrief()).doesNotThrowAnyException();

        ArgumentCaptor<PushLog> captor = ArgumentCaptor.forClass(PushLog.class);
        verify(pushLogRepository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(PushStatus.FAIL);
        assertThat(captor.getValue().error()).isNotBlank();
    }

    @Test
    @DisplayName("给定超长contentMd与未绑定推送用户，when推送，then正文截断3000字带截断标记")
    void givenOverlongContent_whenPush_thenTruncatedTo3000() {
        String longMd = "长".repeat(4000);
        when(briefRepository.findByDate(TODAY)).thenReturn(
                Optional.of(archived(105L, BriefStatus.GENERATED, longMd, null)));

        service.pushBrief();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> linesCaptor = ArgumentCaptor.forClass(List.class);
        verify(pushPort).sendToGroup(eq("【盘前简报】" + TODAY), eq("blue"), linesCaptor.capture());
        String body = linesCaptor.getValue().get(0);
        assertThat(body.length()).isEqualTo(BriefPushService.MAX_CARD_CHARS + "…（已截断）".length());
        assertThat(body).startsWith("长长").endsWith("…（已截断）");
    }

    @Test
    @DisplayName("给定查档抛异常，when调度入口，then顶层吞异常不炸调度线程")
    void givenRepositoryBlowsUp_whenScheduled_thenSwallowed() {
        when(briefRepository.findByDate(TODAY)).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> service.pushBriefScheduled()).doesNotThrowAnyException();
        verify(pushPort, never()).sendToGroup(anyString(), anyString(), anyList());
        verify(pushLogRepository, never()).save(any());
    }

    // ── P4 个性化单发路径（D17）────────────────────────────────────────

    @Test
    @DisplayName("给定绑定用户订阅标的命中窗口内重大条目，when推送，then单发正文=简报+「⭐ 你关注的」附节且不发群")
    void givenBoundUserWithStockHit_whenPush_thenSingleSendWithFocusSection() {
        when(briefRepository.findByDate(TODAY)).thenReturn(
                Optional.of(archived(201L, BriefStatus.GENERATED, CONTENT_MD, null)));
        when(subscriptionRepository.findUserIdsWithPushEnabled()).thenReturn(List.of(7L));
        when(subscriptionService.findOpenId(7L)).thenReturn(Optional.of("ou-7"));
        when(subscriptionRepository.findByUserId(7L)).thenReturn(Optional.of(
                IntelligenceSubscription.defaults(7L)
                        .withStocks(List.of(new SubscriptionStock("600519", "贵州茅台")))));
        when(newsRepository.findMajorSince(WINDOW_START, 80)).thenReturn(List.of(
                news(1L, "贵州茅台中标", List.of("600519"), List.of()),
                news(2L, "宁德时代新品", List.of("300750"), List.of())));

        service.pushBrief();

        verify(pushPort, never()).sendToGroup(anyString(), anyString(), anyList());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> body = ArgumentCaptor.forClass(List.class);
        verify(pushPort).sendToUser(eq("ou-7"), eq("【盘前简报】" + TODAY), eq("blue"), body.capture());
        assertThat(body.getValue()).as("正文=简报 md + 附节标题 + 命中条目（未命中条目剔除）")
                .containsExactly(CONTENT_MD, "⭐ 你关注的", "- 贵州茅台中标——摘要1");
        verify(newsRepository).findMajorSince(WINDOW_START, 80); // 窗口=简报选取窗口，阈值=majorThreshold
        ArgumentCaptor<PushLog> logs = ArgumentCaptor.forClass(PushLog.class);
        verify(pushLogRepository, times(1)).save(logs.capture());
        PushLog log = logs.getValue();
        assertThat(log.userId()).isEqualTo(7L);
        assertThat(log.target()).isEqualTo("ou-7");
        assertThat(log.refId()).isEqualTo(201L);
        assertThat(log.status()).isEqualTo(PushStatus.OK);
    }

    @Test
    @DisplayName("给定绑定用户订阅无任何命中，when推送，then单发仅简报正文不附节")
    void givenBoundUserWithoutHit_whenPush_thenSingleSendWithoutFocusSection() {
        when(briefRepository.findByDate(TODAY)).thenReturn(
                Optional.of(archived(202L, BriefStatus.GENERATED, CONTENT_MD, null)));
        when(subscriptionRepository.findUserIdsWithPushEnabled()).thenReturn(List.of(7L));
        when(subscriptionService.findOpenId(7L)).thenReturn(Optional.of("ou-7"));
        when(subscriptionRepository.findByUserId(7L)).thenReturn(Optional.of(
                IntelligenceSubscription.defaults(7L)
                        .withStocks(List.of(new SubscriptionStock("600519", "贵州茅台")))));
        when(newsRepository.findMajorSince(any(), anyInt())).thenReturn(List.of(
                news(2L, "宁德时代新品", List.of("300750"), List.of())));

        service.pushBrief();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> body = ArgumentCaptor.forClass(List.class);
        verify(pushPort).sendToUser(eq("ou-7"), anyString(), eq("blue"), body.capture());
        assertThat(body.getValue()).as("无命中不附节，正文只有简报 md").containsExactly(CONTENT_MD);
        verify(pushLogRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("给定绑定用户空订阅（无行缺省），when推送，then单发仅简报正文不附节")
    void givenBoundUserWithDefaultEmptySubscription_whenPush_thenNoFocusSection() {
        when(briefRepository.findByDate(TODAY)).thenReturn(
                Optional.of(archived(203L, BriefStatus.GENERATED, CONTENT_MD, null)));
        when(subscriptionRepository.findUserIdsWithPushEnabled()).thenReturn(List.of(7L));
        when(subscriptionService.findOpenId(7L)).thenReturn(Optional.of("ou-7"));
        when(subscriptionRepository.findByUserId(7L)).thenReturn(Optional.empty());

        service.pushBrief();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> body = ArgumentCaptor.forClass(List.class);
        verify(pushPort).sendToUser(eq("ou-7"), anyString(), eq("blue"), body.capture());
        assertThat(body.getValue()).containsExactly(CONTENT_MD);
    }

    @Test
    @DisplayName("给定全部推送用户已绑定，when推送，then只单发不发群（避免双发）")
    void givenAllUsersBound_whenPush_thenNoGroupSend() {
        when(briefRepository.findByDate(TODAY)).thenReturn(
                Optional.of(archived(204L, BriefStatus.GENERATED, CONTENT_MD, null)));
        when(subscriptionRepository.findUserIdsWithPushEnabled()).thenReturn(List.of(7L, 8L));
        when(subscriptionService.findOpenId(7L)).thenReturn(Optional.of("ou-7"));
        when(subscriptionService.findOpenId(8L)).thenReturn(Optional.of("ou-8"));

        service.pushBrief();

        verify(pushPort, never()).sendToGroup(anyString(), anyString(), anyList());
        verify(pushPort, times(2)).sendToUser(anyString(), anyString(), anyString(), anyList());
        verify(pushLogRepository, times(2)).save(any()); // 仅两行单发留痕，无群推行
    }

    @Test
    @DisplayName("给定部分绑定部分未绑定，when推送，then绑定者单发+群兜底各留一行痕（target 分别 openId/chatId）")
    void givenMixedBoundAndUnbound_whenPush_thenSingleAndGroupBothLogged() {
        when(briefRepository.findByDate(TODAY)).thenReturn(
                Optional.of(archived(205L, BriefStatus.GENERATED, CONTENT_MD, null)));
        when(subscriptionRepository.findUserIdsWithPushEnabled()).thenReturn(List.of(7L, 9L));
        when(subscriptionService.findOpenId(7L)).thenReturn(Optional.of("ou-7"));

        service.pushBrief();

        verify(pushPort, times(1)).sendToUser(eq("ou-7"), anyString(), anyString(), anyList());
        verify(pushPort, times(1)).sendToGroup(eq("【盘前简报】" + TODAY), eq("blue"), anyList());
        ArgumentCaptor<PushLog> logs = ArgumentCaptor.forClass(PushLog.class);
        verify(pushLogRepository, times(2)).save(logs.capture());
        assertThat(logs.getAllValues())
                .extracting(PushLog::target)
                .as("单发 target=openId、群推 target=chatId")
                .containsExactlyInAnyOrder("ou-7", "oc_intel");
        assertThat(logs.getAllValues()).extracting(PushLog::userId)
                .containsExactlyInAnyOrder(7L, null);
    }

    @Test
    @DisplayName("给定订阅 stocks 与 industries 双维命中，when推送，then附节按并集过滤（股票命中∪行业命中）")
    void givenStockAndIndustrySubscription_whenPush_thenFocusFiltersByUnion() {
        when(briefRepository.findByDate(TODAY)).thenReturn(
                Optional.of(archived(206L, BriefStatus.GENERATED, CONTENT_MD, null)));
        when(subscriptionRepository.findUserIdsWithPushEnabled()).thenReturn(List.of(7L));
        when(subscriptionService.findOpenId(7L)).thenReturn(Optional.of("ou-7"));
        when(subscriptionRepository.findByUserId(7L)).thenReturn(Optional.of(
                IntelligenceSubscription.defaults(7L)
                        .withStocks(List.of(new SubscriptionStock("600519", "贵州茅台")))
                        .withIndustries(List.of("801010"))));
        when(newsRepository.findMajorSince(any(), anyInt())).thenReturn(List.of(
                news(1L, "行业政策落地", List.of(), List.of("801010")),
                news(2L, "无关条目", List.of("000001"), List.of("801020")),
                news(3L, "贵州茅台中标", List.of("600519"), List.of())));

        service.pushBrief();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> body = ArgumentCaptor.forClass(List.class);
        verify(pushPort).sendToUser(eq("ou-7"), anyString(), eq("blue"), body.capture());
        assertThat(body.getValue()).as("股票命中与行业命中都进附节，无关条目剔除，顺序随窗口倒序")
                .containsExactly(CONTENT_MD, "⭐ 你关注的",
                        "- 行业政策落地——摘要1", "- 贵州茅台中标——摘要3");
    }

    @Test
    @DisplayName("给定窗口内命中超过 10 条，when推送，then附节至多取前 10 条")
    void givenMoreThanTenHits_whenPush_thenFocusCappedAtTen() {
        when(briefRepository.findByDate(TODAY)).thenReturn(
                Optional.of(archived(207L, BriefStatus.GENERATED, CONTENT_MD, null)));
        when(subscriptionRepository.findUserIdsWithPushEnabled()).thenReturn(List.of(7L));
        when(subscriptionService.findOpenId(7L)).thenReturn(Optional.of("ou-7"));
        when(subscriptionRepository.findByUserId(7L)).thenReturn(Optional.of(
                IntelligenceSubscription.defaults(7L)
                        .withStocks(List.of(new SubscriptionStock("600519", "贵州茅台")))));
        List<NewsRecord> twelveHits = java.util.stream.LongStream.rangeClosed(1, 12)
                .mapToObj(id -> news(id, "命中" + id, List.of("600519"), List.of()))
                .toList();
        when(newsRepository.findMajorSince(any(), anyInt())).thenReturn(twelveHits);

        service.pushBrief();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> body = ArgumentCaptor.forClass(List.class);
        verify(pushPort).sendToUser(eq("ou-7"), anyString(), eq("blue"), body.capture());
        assertThat(body.getValue()).as("简报正文 + 附节标题 + 至多 10 条命中")
                .hasSize(12)
                .containsExactlyElementsOf(java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(CONTENT_MD, "⭐ 你关注的"),
                        java.util.stream.LongStream.rangeClosed(1, 10)
                                .mapToObj(id -> "- 命中" + id + "——摘要" + id))
                        .toList());
    }

    @Test
    @DisplayName("给定FAILED档与绑定用户，when推送，then单发红色失败提示卡不附节不查附节池")
    void givenFailedBriefAndBoundUser_whenPush_thenSingleFailureCardWithoutFocus() {
        when(briefRepository.findByDate(TODAY)).thenReturn(
                Optional.of(archived(208L, BriefStatus.FAILED, "# 占位", "llm down")));
        when(subscriptionRepository.findUserIdsWithPushEnabled()).thenReturn(List.of(7L));
        when(subscriptionService.findOpenId(7L)).thenReturn(Optional.of("ou-7"));

        service.pushBrief();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> body = ArgumentCaptor.forClass(List.class);
        verify(pushPort).sendToUser(eq("ou-7"), anyString(), eq("red"), body.capture());
        assertThat(String.join("\n", body.getValue())).contains("今日简报生成失败").contains("llm down");
        assertThat(body.getValue()).noneMatch(line -> line.contains("你关注的"));
        verify(newsRepository, never()).findMajorSince(any(), anyInt());
    }

    @Test
    @DisplayName("给定飞书单发返回失败，when推送，then该用户留痕FAIL其余用户不受影响")
    void givenSingleSendFails_whenPush_thenFailLoggedForThatUserOnly() {
        when(briefRepository.findByDate(TODAY)).thenReturn(
                Optional.of(archived(209L, BriefStatus.GENERATED, CONTENT_MD, null)));
        when(subscriptionRepository.findUserIdsWithPushEnabled()).thenReturn(List.of(7L, 8L));
        when(subscriptionService.findOpenId(7L)).thenReturn(Optional.of("ou-7"));
        when(subscriptionService.findOpenId(8L)).thenReturn(Optional.of("ou-8"));
        when(pushPort.sendToUser(eq("ou-7"), anyString(), anyString(), anyList())).thenReturn(false);

        service.pushBrief();

        ArgumentCaptor<PushLog> logs = ArgumentCaptor.forClass(PushLog.class);
        verify(pushLogRepository, times(2)).save(logs.capture());
        assertThat(logs.getAllValues()).extracting(PushLog::target)
                .containsExactlyInAnyOrder("ou-7", "ou-8");
        assertThat(logs.getAllValues()).filteredOn(log -> log.target().equals("ou-7"))
                .singleElement()
                .satisfies(log -> {
                    assertThat(log.status()).isEqualTo(PushStatus.FAIL);
                    assertThat(log.error()).isNotBlank();
                });
    }

    @Test
    @DisplayName("给定一名用户绑定查询抛异常而另一名已绑定，when推送，then异常用户跳过单发归入群兜底且不影响他人")
    void givenFindOpenIdBlowsUpForOneUser_whenPush_thenOthersStillSentAndFailedUserCoveredByGroup() {
        when(briefRepository.findByDate(TODAY)).thenReturn(
                Optional.of(archived(210L, BriefStatus.GENERATED, CONTENT_MD, null)));
        when(subscriptionRepository.findUserIdsWithPushEnabled()).thenReturn(List.of(7L, 8L));
        when(subscriptionService.findOpenId(7L)).thenThrow(new IllegalStateException("db down"));
        when(subscriptionService.findOpenId(8L)).thenReturn(Optional.of("ou-8"));

        assertThatCode(() -> service.pushBrief()).doesNotThrowAnyException();

        verify(pushPort, times(1)).sendToUser(eq("ou-8"), anyString(), anyString(), anyList());
        verify(pushPort, times(1)).sendToGroup(anyString(), anyString(), anyList()); // 异常用户由群兜底防失联
        ArgumentCaptor<PushLog> logs = ArgumentCaptor.forClass(PushLog.class);
        verify(pushLogRepository, times(2)).save(logs.capture());
        assertThat(logs.getAllValues()).extracting(PushLog::target)
                .as("异常用户无单发留痕（无 openId 可记），仅有他人单发行与群推行")
                .containsExactlyInAnyOrder("ou-8", "oc_intel");
    }

    @Test
    @DisplayName("给定附节候选查询抛异常，when推送，then降级不附节单发正文照发不留失败痕")
    void givenMajorPoolQueryBlowsUp_whenPush_thenDegradeToNoFocusSection() {
        when(briefRepository.findByDate(TODAY)).thenReturn(
                Optional.of(archived(211L, BriefStatus.GENERATED, CONTENT_MD, null)));
        when(subscriptionRepository.findUserIdsWithPushEnabled()).thenReturn(List.of(7L));
        when(subscriptionService.findOpenId(7L)).thenReturn(Optional.of("ou-7"));
        when(subscriptionRepository.findByUserId(7L)).thenReturn(Optional.of(
                IntelligenceSubscription.defaults(7L)
                        .withStocks(List.of(new SubscriptionStock("600519", "贵州茅台")))));
        when(newsRepository.findMajorSince(any(), anyInt()))
                .thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> service.pushBrief()).doesNotThrowAnyException();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> body = ArgumentCaptor.forClass(List.class);
        verify(pushPort).sendToUser(eq("ou-7"), anyString(), eq("blue"), body.capture());
        assertThat(body.getValue()).as("附节是增值信息，查询失败降级不附节不挡主正文").containsExactly(CONTENT_MD);
        verify(pushLogRepository, times(1)).save(any());
    }
}
