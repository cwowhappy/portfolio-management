package com.portfolio.invest.application.intelligence;

import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.intelligence.BriefRepository;
import com.portfolio.invest.domain.intelligence.BriefStatus;
import com.portfolio.invest.domain.intelligence.DailyBrief;
import com.portfolio.invest.domain.intelligence.PushLog;
import com.portfolio.invest.domain.intelligence.PushLogRepository;
import com.portfolio.invest.domain.intelligence.PushStatus;
import com.portfolio.invest.domain.intelligence.PushType;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 盘前简报推送（D11）：交易日 08:30 读当日档群推 + push_log 留痕。切片要点：
 * GENERATED/EMPTY_SIMPLE 照发（标题【盘前简报】日期、正文 contentMd、超长截断 3000）、
 * FAILED 档发一行失败提示卡（运维感知）、无档（漏跑）不发不补陈旧、非交易日/未配置跳过、
 * 推送失败留痕 FAIL 不抛、留痕字段（target=chatId、ref_table/ref_id 指向简报行）、
 * 调度顶层吞异常。
 */
class BriefPushServiceTest {

    /** 上海 2026-09-29（周二）09:30（UTC 01:30）。 */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-29T01:30:00Z"), ZoneId.of("Asia/Shanghai"));
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 29);
    private static final String CONTENT_MD = "# 盘前情报速递\n\n## 宏观与政策\n- 条目一";

    private final BriefRepository briefRepository = mock(BriefRepository.class);
    private final IntelligencePushPort pushPort = mock(IntelligencePushPort.class);
    private final PushLogRepository pushLogRepository = mock(PushLogRepository.class);
    private final TradingCalendarPort tradingCalendar = mock(TradingCalendarPort.class);
    private final InvestProperties props = new InvestProperties();
    private BriefPushService service;

    @BeforeEach
    void setUp() {
        props.getIm().setAppId("cli_x");
        props.getIm().setAppSecret("sec");
        props.getIm().setChatId("oc_intel");
        service = new BriefPushService(briefRepository, pushPort, pushLogRepository,
                tradingCalendar, props, CLOCK);
        when(tradingCalendar.isTradingDay(any())).thenReturn(true);
        when(pushPort.sendToGroup(anyString(), anyString(), anyList())).thenReturn(true);
    }

    /** 模拟 DB 读出档（id 非 null——留痕 ref_id 消费）。 */
    private static DailyBrief archived(Long id, BriefStatus status, String contentMd, String failReason) {
        return new DailyBrief(id, TODAY, contentMd, List.of(), status, failReason, "m",
                CLOCK.instant());
    }

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
    @DisplayName("给定GENERATED档，when推送，then标题带日期正文为contentMd且留痕OK全字段")
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
    @DisplayName("给定EMPTY_SIMPLE档，when推送，then空简版照发照留痕")
    void givenEmptySimpleBrief_whenPush_thenStillSent() {
        String emptyMd = "# 盘前情报速递\n\n今日无重大情报。";
        when(briefRepository.findByDate(TODAY)).thenReturn(
                Optional.of(archived(102L, BriefStatus.EMPTY_SIMPLE, emptyMd, null)));

        service.pushBrief();

        verify(pushPort).sendToGroup(eq("【盘前简报】" + TODAY), eq("blue"), eq(List.of(emptyMd)));
        verify(pushLogRepository).save(any());
    }

    @Test
    @DisplayName("给定FAILED档，when推送，then发一行生成失败提示卡并留痕")
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
    @DisplayName("给定超长contentMd，when推送，then正文截断3000字带截断标记")
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
}
