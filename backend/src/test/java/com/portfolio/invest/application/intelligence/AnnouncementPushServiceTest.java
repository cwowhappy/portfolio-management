package com.portfolio.invest.application.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.invest.application.research.IntelligenceSubscriptionHook;
import com.portfolio.invest.application.research.IntelligenceSubscriptionHook.IntelligenceTarget;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.intelligence.AnnouncementMetrics;
import com.portfolio.invest.domain.intelligence.AnnouncementRecord;
import com.portfolio.invest.domain.intelligence.AnnouncementRepository;
import com.portfolio.invest.domain.intelligence.AnnouncementType;
import com.portfolio.invest.domain.intelligence.ExtractStatus;
import com.portfolio.invest.domain.intelligence.FeishuBindingRepository;
import com.portfolio.invest.domain.intelligence.IntelligenceSubscription;
import com.portfolio.invest.domain.intelligence.PushLog;
import com.portfolio.invest.domain.intelligence.PushLogRepository;
import com.portfolio.invest.domain.intelligence.PushStatus;
import com.portfolio.invest.domain.intelligence.PushType;
import com.portfolio.invest.domain.intelligence.SubscriptionRepository;
import com.portfolio.invest.domain.intelligence.SubscriptionStock;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 公告定向推送单元切片（mock 仓库/绑定/hook/推送端口/留痕仓库；订阅聚合用真实 domain 对象）：
 * D12/D15/决策 #26 union 命中（手动订阅 ∪ POSITION 持仓 hook）→ 按 (announcement, user)
 * 聚合一次 sendToUser（同用户多路命中去重；多条公告逐条推送不跨公告聚合）→ push_log 留痕
 * （OK/FAIL/SKIPPED_NO_BINDING 三态）→ 幂等查重跳过 → metrics 空降级标题+链接 →
 * journal 留痕方法位空实现不炸（P4 激活）→ 单公告/单用户/顶层三层异常隔离。
 */
class AnnouncementPushServiceTest {

    /** 上海 2026-09-29 22:45（UTC 14:45）——抽取批末触发时刻锚点。 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-29T14:45:00Z"), ZoneId.of("Asia/Shanghai"));
    private static final Instant SINCE = Instant.parse("2026-09-29T14:45:00Z");
    private static final String STOCK = "600519";
    private static final String OPEN_ID = "ou-123";

    private final AnnouncementRepository announcementRepository = mock(AnnouncementRepository.class);
    private final SubscriptionRepository subscriptionRepository = mock(SubscriptionRepository.class);
    private final IntelligenceSubscriptionHook hook = mock(IntelligenceSubscriptionHook.class);
    private final FeishuBindingRepository bindingRepository = mock(FeishuBindingRepository.class);
    private final IntelligencePushPort pushPort = mock(IntelligencePushPort.class);
    private final PushLogRepository pushLogRepository = mock(PushLogRepository.class);
    private final InvestProperties props = new InvestProperties();
    private AnnouncementPushService service;

    @BeforeEach
    void setUp() {
        props.getIm().setAppId("cli-app");
        props.getIm().setAppSecret("secret");
        service = new AnnouncementPushService(announcementRepository, subscriptionRepository, hook,
                bindingRepository, pushPort, pushLogRepository, props, CLOCK);
        when(hook.activePositionTargets()).thenReturn(List.of());
        when(subscriptionRepository.findAllWithStock(anyString())).thenReturn(List.of());
        when(bindingRepository.findOpenIdByUserId(anyLong())).thenReturn(Optional.of(OPEN_ID));
        when(pushLogRepository.existsAnnouncementPush(anyLong(), anyLong())).thenReturn(false);
        when(pushPort.sendToUser(anyString(), anyString(), anyString(), any())).thenReturn(true);
    }

    @Test
    @DisplayName("给定同标的两条公告命中同一订阅用户，when推送，then逐条各推一次不跨公告聚合，各留一行 OK")
    void givenTwoAnnouncementsSameStockSameUser_whenPushExtracted_thenTwoSeparateSendsAndLogs() {
        when(announcementRepository.findExtractedMajorSince(SINCE)).thenReturn(List.of(
                announcement(1L, "2026年半年度报告"), announcement(2L, "2026年半年度报告摘要")));
        when(subscriptionRepository.findAllWithStock(STOCK)).thenReturn(List.of(subscription(7L)));

        service.pushExtracted(SINCE);

        verify(pushPort, times(2)).sendToUser(anyString(), anyString(), anyString(), any());
        ArgumentCaptor<PushLog> logs = captor();
        verify(pushLogRepository, times(2)).save(logs.capture());
        assertThat(logs.getAllValues()).allSatisfy(log -> {
            assertThat(log.userId()).isEqualTo(7L);
            assertThat(log.pushType()).isEqualTo(PushType.ANNOUNCEMENT);
            assertThat(log.status()).isEqualTo(PushStatus.OK);
            assertThat(log.target()).isEqualTo(OPEN_ID);
        });
        assertThat(logs.getAllValues()).extracting(PushLog::refId).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    @DisplayName("给定一条公告分别命中订阅用户与 hook 持仓用户，when推送，then两用户各自单发各留一行 OK")
    void givenOneAnnouncementHitBySubscriberAndHookUser_whenPushExtracted_thenEachUserPushedOnce() {
        when(announcementRepository.findExtractedMajorSince(SINCE))
                .thenReturn(List.of(announcement(1L, "2026年半年度报告")));
        when(subscriptionRepository.findAllWithStock(STOCK)).thenReturn(List.of(subscription(7L)));
        when(hook.activePositionTargets())
                .thenReturn(List.of(new IntelligenceTarget(9L, 100L, STOCK, "贵州茅台")));
        when(bindingRepository.findOpenIdByUserId(7L)).thenReturn(Optional.of("ou-subscriber"));
        when(bindingRepository.findOpenIdByUserId(9L)).thenReturn(Optional.of("ou-holder"));

        service.pushExtracted(SINCE);

        ArgumentCaptor<PushLog> logs = captor();
        verify(pushLogRepository, times(2)).save(logs.capture());
        assertThat(logs.getAllValues()).extracting(PushLog::userId).containsExactlyInAnyOrder(7L, 9L);
        ArgumentCaptor<String> openIds = ArgumentCaptor.forClass(String.class);
        verify(pushPort, times(2)).sendToUser(openIds.capture(), anyString(), anyString(), any());
        assertThat(openIds.getAllValues())
                .as("两用户按各自 open_id 单发")
                .containsExactlyInAnyOrder("ou-subscriber", "ou-holder");
    }

    @Test
    @DisplayName("给定同一用户经订阅与 hook 双路命中同一公告，when推送，then合并为一次推送一行留痕（union 去重）")
    void givenUserHitViaBothSubscriptionAndHook_whenPushExtracted_thenSingleSendPerAnnouncementUser() {
        when(announcementRepository.findExtractedMajorSince(SINCE))
                .thenReturn(List.of(announcement(1L, "2026年半年度报告")));
        when(subscriptionRepository.findAllWithStock(STOCK)).thenReturn(List.of(subscription(7L)));
        when(hook.activePositionTargets()).thenReturn(List.of(
                new IntelligenceTarget(7L, 100L, STOCK, "贵州茅台"),
                new IntelligenceTarget(7L, 101L, STOCK, "贵州茅台")));

        service.pushExtracted(SINCE);

        verify(pushPort, times(1)).sendToUser(anyString(), anyString(), anyString(), any());
        verify(pushLogRepository, times(1)).save(captor().capture());
    }

    @Test
    @DisplayName("给定命中用户无飞书绑定，when推送，then不单发且留痕 SKIPPED_NO_BINDING（userId/ref 仍在）")
    void givenNoBinding_whenPushExtracted_thenSkippedNoBindingLoggedWithoutSend() {
        when(announcementRepository.findExtractedMajorSince(SINCE))
                .thenReturn(List.of(announcement(1L, "2026年半年度报告")));
        when(subscriptionRepository.findAllWithStock(STOCK)).thenReturn(List.of(subscription(7L)));
        when(bindingRepository.findOpenIdByUserId(7L)).thenReturn(Optional.empty());

        service.pushExtracted(SINCE);

        verify(pushPort, never()).sendToUser(anyString(), anyString(), anyString(), any());
        ArgumentCaptor<PushLog> logs = captor();
        verify(pushLogRepository, times(1)).save(logs.capture());
        PushLog log = logs.getValue();
        assertThat(log.status()).isEqualTo(PushStatus.SKIPPED_NO_BINDING);
        assertThat(log.userId()).as("未绑定仍归属用户").isEqualTo(7L);
        assertThat(log.refTable()).isEqualTo("intelligence_announcement");
        assertThat(log.refId()).isEqualTo(1L);
        assertThat(log.target()).as("target 列 NOT NULL，以哨兵占位").isEqualTo("UNBOUND");
    }

    @Test
    @DisplayName("给定该用户该公告已有 OK/SKIPPED 留痕，when推送，then幂等跳过（不再单发不再留痕）")
    void givenExistingPushLog_whenPushExtracted_thenSkippedEntirely() {
        when(announcementRepository.findExtractedMajorSince(SINCE))
                .thenReturn(List.of(announcement(1L, "2026年半年度报告")));
        when(subscriptionRepository.findAllWithStock(STOCK)).thenReturn(List.of(subscription(7L)));
        when(pushLogRepository.existsAnnouncementPush(1L, 7L)).thenReturn(true);

        service.pushExtracted(SINCE);

        verify(bindingRepository, never()).findOpenIdByUserId(anyLong());
        verify(pushPort, never()).sendToUser(anyString(), anyString(), anyString(), any());
        verify(pushLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("给定 metrics 齐备的业绩公告，when推送，then卡片正文含类型/关键数字行/原文链接")
    void givenMetricsPresent_whenPushExtracted_thenBodyContainsKeyNumbers() {
        when(announcementRepository.findExtractedMajorSince(SINCE)).thenReturn(List.of(
                announcement(1L, "2026年半年度报告", fullMetrics(),
                        List.of(AnnouncementType.PERIODIC_REPORT), "https://static.cninfo.com.cn/a.pdf")));
        when(subscriptionRepository.findAllWithStock(STOCK)).thenReturn(List.of(subscription(7L)));

        service.pushExtracted(SINCE);

        List<String> lines = sentBodyLines();
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("类型：").contains("定期报告"));
        assertThat(lines).contains("营业收入：128.56 亿元", "归母净利润：31.2 亿元",
                "净利润同比：+25.3%", "扣非净利润：30.05 亿元", "分红：每10股派2元");
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("原文：")
                .contains("https://static.cninfo.com.cn/a.pdf"));
        assertThat(sentTitle()).startsWith("【公告提醒】贵州茅台：");
    }

    @Test
    @DisplayName("给定 metrics 全空的非业绩公告（宽栏目/杂项形态），when推送，then降级正文标题行+链接无数字行")
    void givenMetricsAbsent_whenPushExtracted_thenBodyFallsBackToTitleAndLink() {
        when(announcementRepository.findExtractedMajorSince(SINCE)).thenReturn(List.of(
                announcement(1L, "关于回购公司股份的进展公告", null,
                        List.of(AnnouncementType.BUYBACK), "https://static.cninfo.com.cn/b.pdf")));
        when(subscriptionRepository.findAllWithStock(STOCK)).thenReturn(List.of(subscription(7L)));

        service.pushExtracted(SINCE);

        List<String> lines = sentBodyLines();
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("类型：").contains("回购"));
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("标题：")
                .contains("关于回购公司股份的进展公告"));
        assertThat(lines).noneMatch(line -> line.contains("亿元") || line.contains("同比"));
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("原文：")
                .contains("https://static.cninfo.com.cn/b.pdf"));
    }

    @Test
    @DisplayName("给定公告无 annTypes 标签也无源站栏目，when推送，then类型行降级为其他")
    void givenNoTypeLabels_whenPushExtracted_thenTypeLineFallsBackToOther() {
        when(announcementRepository.findExtractedMajorSince(SINCE)).thenReturn(List.of(
                noSourceColumnAnnouncement(1L, "关于召开股东大会的通知", "https://x/c.pdf")));
        when(subscriptionRepository.findAllWithStock(STOCK)).thenReturn(List.of(subscription(7L)));

        service.pushExtracted(SINCE);

        assertThat(sentBodyLines()).anySatisfy(line -> assertThat(line).isEqualTo("类型：其他"));
    }

    @Test
    @DisplayName("给定单发返回失败，when推送，then留痕 FAIL 并带原因")
    void givenSendReturnsFalse_whenPushExtracted_thenFailLogged() {
        when(announcementRepository.findExtractedMajorSince(SINCE))
                .thenReturn(List.of(announcement(1L, "2026年半年度报告")));
        when(subscriptionRepository.findAllWithStock(STOCK)).thenReturn(List.of(subscription(7L)));
        when(pushPort.sendToUser(anyString(), anyString(), anyString(), any())).thenReturn(false);

        service.pushExtracted(SINCE);

        ArgumentCaptor<PushLog> logs = captor();
        verify(pushLogRepository, times(1)).save(logs.capture());
        assertThat(logs.getValue().status()).isEqualTo(PushStatus.FAIL);
        assertThat(logs.getValue().error()).isNotBlank();
    }

    @Test
    @DisplayName("给定第二条公告查询订阅时抛异常，when推送，then首尾两条照推且顶层不抛（异常隔离）")
    void givenSecondAnnouncementExplodes_whenPushExtracted_thenOthersStillPushedAndNoThrow() {
        when(announcementRepository.findExtractedMajorSince(SINCE)).thenReturn(List.of(
                announcement(1L, "第一条公告"), announcement(2L, "第二条公告"), announcement(3L, "第三条公告")));
        when(subscriptionRepository.findAllWithStock(STOCK))
                .thenReturn(List.of(subscription(7L)))
                .thenThrow(new RuntimeException("db down"))
                .thenReturn(List.of(subscription(7L)));

        assertThatCode(() -> service.pushExtracted(SINCE)).doesNotThrowAnyException();

        verify(pushPort, times(2)).sendToUser(anyString(), anyString(), anyString(), any());
        verify(pushLogRepository, times(2)).save(captor().capture());
    }

    @Test
    @DisplayName("给定窗口内无完成抽取的公告，when推送，then快退不查 hook 不推送")
    void givenEmptyWindow_whenPushExtracted_thenNothingQueriedBeyondWindow() {
        when(announcementRepository.findExtractedMajorSince(SINCE)).thenReturn(List.of());

        service.pushExtracted(SINCE);

        verify(hook, never()).activePositionTargets();
        verify(pushPort, never()).sendToUser(anyString(), anyString(), anyString(), any());
        verify(pushLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("给定飞书 im 未配置，when推送，then整体跳过不留任何痕（部署状态非推送失败）")
    void givenImUnconfigured_whenPushExtracted_thenSkippedWholesale() {
        props.getIm().setAppId("");
        props.getIm().setAppSecret("");

        service.pushExtracted(SINCE);

        verify(announcementRepository, never()).findExtractedMajorSince(any());
        verify(pushLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("给定 hook 命中项目且用户已绑定，when推送，then流程完整走通（journal 留痕方法位空实现不炸）")
    void givenHookProjectHitWithBinding_whenPushExtracted_thenFlowCompletes() {
        when(announcementRepository.findExtractedMajorSince(SINCE))
                .thenReturn(List.of(announcement(1L, "2026年半年度报告", fullMetrics(),
                        List.of(AnnouncementType.PERIODIC_REPORT), "https://x/a.pdf")));
        when(hook.activePositionTargets())
                .thenReturn(List.of(new IntelligenceTarget(7L, 100L, STOCK, "贵州茅台")));

        assertThatCode(() -> service.pushExtracted(SINCE)).doesNotThrowAnyException();

        verify(pushPort, times(1)).sendToUser(anyString(), anyString(), anyString(), any());
        verify(pushLogRepository, times(1)).save(captor().capture());
    }

    // ── fixture 助手 ───────────────────────────────────────────────

    private static AnnouncementMetrics fullMetrics() {
        return new AnnouncementMetrics(new BigDecimal("128.56"), new BigDecimal("31.2"),
                new BigDecimal("25.3"), new BigDecimal("30.05"), null, "每10股派2元", List.of("毛利率"));
    }

    private static AnnouncementRecord announcement(long id, String title) {
        return announcement(id, title, fullMetrics(), List.of(AnnouncementType.PERIODIC_REPORT), null);
    }

    private static AnnouncementRecord announcement(long id, String title, AnnouncementMetrics metrics,
                                                   List<AnnouncementType> annTypes, String pdfUrl) {
        return new AnnouncementRecord(id, "cninfo", "ext-" + id, STOCK, "贵州茅台", title,
                "半年度报告", true, Instant.parse("2026-09-29T12:00:00Z"), pdfUrl,
                Instant.parse("2026-09-29T13:00:00Z"), metrics, annTypes, "pdf-text",
                ExtractStatus.SUCCESS, "deepseek-chat", Instant.parse("2026-09-29T14:45:00Z"));
    }

    /** annTypeSource 亦为空（杂项公告形态：无标签无栏目）。 */
    private static AnnouncementRecord noSourceColumnAnnouncement(long id, String title, String pdfUrl) {
        return new AnnouncementRecord(id, "eastmoney", "ext-" + id, STOCK, "贵州茅台", title,
                null, true, Instant.parse("2026-09-29T12:00:00Z"), pdfUrl,
                Instant.parse("2026-09-29T13:00:00Z"), null, List.of(), null,
                ExtractStatus.SUCCESS, null, Instant.parse("2026-09-29T14:45:00Z"));
    }

    private static IntelligenceSubscription subscription(long userId) {
        return IntelligenceSubscription.defaults(userId)
                .withStocks(List.of(new SubscriptionStock(STOCK, "贵州茅台")));
    }

    private static ArgumentCaptor<PushLog> captor() {
        return ArgumentCaptor.forClass(PushLog.class);
    }

    private List<String> sentBodyLines() {
        ArgumentCaptor<List<String>> lines = ArgumentCaptor.forClass(List.class);
        verify(pushPort, times(1)).sendToUser(anyString(), anyString(), anyString(), lines.capture());
        return lines.getValue();
    }

    private String sentTitle() {
        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        verify(pushPort, times(1)).sendToUser(eq(OPEN_ID), title.capture(), anyString(), any());
        return title.getValue();
    }
}
