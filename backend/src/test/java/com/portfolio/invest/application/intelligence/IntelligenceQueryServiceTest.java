package com.portfolio.invest.application.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.invest.application.research.IntelligenceSubscriptionHook;
import com.portfolio.invest.application.research.IntelligenceSubscriptionHook.IntelligenceTarget;
import com.portfolio.invest.domain.intelligence.AnnouncementMetrics;
import com.portfolio.invest.domain.intelligence.AnnouncementRecord;
import com.portfolio.invest.domain.intelligence.AnnouncementRepository;
import com.portfolio.invest.domain.intelligence.AnnouncementType;
import com.portfolio.invest.domain.intelligence.Direction;
import com.portfolio.invest.domain.intelligence.ExtractStatus;
import com.portfolio.invest.domain.intelligence.IntelligenceSubscription;
import com.portfolio.invest.domain.intelligence.MacroPoint;
import com.portfolio.invest.domain.intelligence.NewsRecord;
import com.portfolio.invest.domain.intelligence.NewsRepository;
import com.portfolio.invest.domain.intelligence.PageResult;
import com.portfolio.invest.domain.intelligence.PolicyConfidence;
import com.portfolio.invest.domain.intelligence.PolicyDirection;
import com.portfolio.invest.domain.intelligence.PolicyEvent;
import com.portfolio.invest.domain.intelligence.PolicyRepository;
import com.portfolio.invest.domain.intelligence.PolicyStrength;
import com.portfolio.invest.domain.intelligence.SubscriptionRepository;
import com.portfolio.invest.domain.intelligence.SubscriptionStock;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 情报检索用例（MS-20 Task 12 最小版 + MS-21 Task 8 公告检索 + MS-22 Task 6 宏观简报）：
 * limit 夹紧 1..20 + 过滤器→PageQuery 映射 + 合并视图→条目视图映射（news：AI 摘要优先
 * 退化源站摘要；announcement：八字段直传）+ 公告 scope 三态（all 不过滤 / subscription
 * 订阅标的集空集短路 / holdings hook 按 userId 过滤）+ macroBrief（指标串解析归一去重、
 * policyDays 夹紧 1..90、缺失指标显式、政策九字段含 isPolicy 标记）。P4 扩四区块查询。
 */
class IntelligenceQueryServiceTest {

    /** 固定时钟：上海 2026-10-03 10:15（macroBrief 政策窗口「今日」与 generatedAt 可确定化）。 */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-03T02:15:00Z"), ZoneId.of("Asia/Shanghai"));

    private final NewsRepository newsRepository = mock(NewsRepository.class);
    private final AnnouncementRepository announcementRepository = mock(AnnouncementRepository.class);
    private final SubscriptionRepository subscriptionRepository = mock(SubscriptionRepository.class);
    private final IntelligenceSubscriptionHook subscriptionHook = mock(IntelligenceSubscriptionHook.class);
    private final MacroQueryService macroQueryService = mock(MacroQueryService.class);
    private final PolicyRepository policyRepository = mock(PolicyRepository.class);
    private IntelligenceQueryService service;

    @BeforeEach
    void setUp() {
        service = new IntelligenceQueryService(newsRepository, announcementRepository,
                subscriptionRepository, subscriptionHook, macroQueryService, policyRepository,
                FIXED_CLOCK);
        // macroBrief 桩缺省（mock List 返回 null 会 NPE，各测试按需覆盖）
        when(macroQueryService.latest()).thenReturn(List.of());
        when(macroQueryService.series(any(), anyInt())).thenReturn(List.of());
        when(policyRepository.searchEvents(any()))
                .thenReturn(new PageResult<>(List.of(), 0, 1, 20));
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

    // ===== MS-21 Task 8：searchAnnouncements（scope 三态 + 过滤器映射 + 条目映射）=====

    /** 已抽取 SUCCESS 的公告合并视图（metrics/annTypes 由调用方给）。 */
    private static AnnouncementRecord announcement(String stockCode, String title,
            AnnouncementMetrics metrics, List<AnnouncementType> annTypes) {
        return new AnnouncementRecord(1L, "cninfo", "ext-" + stockCode, stockCode,
                "标的" + stockCode, title, "公司公告", true,
                Instant.parse("2026-09-28T13:00:00Z"), "https://x/" + stockCode + ".pdf",
                Instant.parse("2026-09-28T14:00:00Z"), metrics, annTypes, null,
                ExtractStatus.SUCCESS, "deepseek-chat", Instant.parse("2026-09-28T15:00:00Z"));
    }

    private static AnnouncementSearchFilter announcementFilter(
            AnnouncementScope scope, String stock) {
        return new AnnouncementSearchFilter(null, stock, null, null, null, scope, null);
    }

    /** 桩：7L 用户订阅给定标的码集合。 */
    private void stubSubscription(String... stockCodes) {
        when(subscriptionRepository.findByUserId(7L)).thenReturn(Optional.of(
                IntelligenceSubscription.reconstitute(7L, true, List.of(),
                        java.util.Arrays.stream(stockCodes)
                                .map(code -> new SubscriptionStock(code, null))
                                .toList(),
                        Instant.parse("2026-09-01T00:00:00Z"))));
    }

    @Test
    @DisplayName("给定 scope=all 与全参过滤器，when公告检索，then PageQuery 十参映射且不做 scope 过滤")
    void givenAllScope_whenSearchAnnouncements_thenMapsPageQueryWithoutScopeFilter() {
        when(announcementRepository.search(any())).thenReturn(new PageResult<>(List.of(
                announcement("000001", "平安银行公告", null, null)), 27, 1, 10));

        var result = service.searchAnnouncements(7L, new AnnouncementSearchFilter("回购", "600519",
                AnnouncementType.BUYBACK, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 28),
                AnnouncementScope.ALL, null));

        verify(announcementRepository).search(argThat(q ->
                q.page() == 1 && q.pageSize() == 10 && "回购".equals(q.keyword())
                        && "600519".equals(q.stockCode()) && q.industryCode() == null
                        && LocalDate.of(2026, 9, 1).equals(q.from())
                        && LocalDate.of(2026, 9, 28).equals(q.to())
                        && q.minImportance() == null && q.type() == AnnouncementType.BUYBACK
                        && q.major() == null));
        assertThat(result.items()).as("all 不做 scope 内存过滤（范围外标的也命中）").hasSize(1);
        assertThat(result.total()).isEqualTo(27);
        assertThat(result.scopeMessage()).isNull();
    }

    @Test
    @DisplayName("给定 limit 变体，when公告检索，then 夹紧 1..20：null→10、999→20、0→1")
    void givenLimitVariants_whenSearchAnnouncements_thenClampedIntoPageQuery() {
        when(announcementRepository.search(any())).thenReturn(new PageResult<>(List.of(), 0, 1, 10));

        service.searchAnnouncements(7L, new AnnouncementSearchFilter(null, null, null, null, null, null, null));
        service.searchAnnouncements(7L, new AnnouncementSearchFilter(null, null, null, null, null, null, 999));
        service.searchAnnouncements(7L, new AnnouncementSearchFilter(null, null, null, null, null, null, 0));

        verifyAnnouncementPage(1, 10);
        verifyAnnouncementPage(1, 20);
        verifyAnnouncementPage(1, 1);
    }

    @Test
    @DisplayName("给定 scope=subscription 且订阅含两标的，when检索，then 条目按标的集内存过滤、total 为页内命中数")
    void givenSubscriptionScope_whenSearchAnnouncements_thenFilteredBySubscriptionStocks() {
        when(subscriptionRepository.findByUserId(7L)).thenReturn(Optional.of(
                IntelligenceSubscription.reconstitute(7L, true, List.of(),
                        List.of(new SubscriptionStock("600519", "贵州茅台"),
                                new SubscriptionStock("300750", "宁德时代")),
                        Instant.parse("2026-09-01T00:00:00Z"))));
        when(announcementRepository.search(any())).thenReturn(new PageResult<>(List.of(
                announcement("600519", "茅台回购公告", null, List.of(AnnouncementType.BUYBACK)),
                announcement("000001", "范围外公告", null, null),
                announcement("300750", "宁德业绩预告", null, List.of(AnnouncementType.EARNINGS_FORECAST))),
                30, 1, 10));

        var result = service.searchAnnouncements(7L, announcementFilter(AnnouncementScope.SUBSCRIPTION, null));

        assertThat(result.items()).extracting(v -> v.title())
                .containsExactly("茅台回购公告", "宁德业绩预告");
        assertThat(result.total()).as("超采页 scope 命中且裁回 limit 后的条数（最小版口径，非全库精确 total）").isEqualTo(2);
        assertThat(result.scopeMessage()).isNull();
        verify(announcementRepository).search(argThat(q -> q.stockCode() == null)); // scope 不注入 SQL
    }

    @Test
    @DisplayName("给定 scope=subscription 且无订阅行，when检索，then defaults 物化空集→引导语且不打仓库")
    void givenSubscriptionScopeEmpty_whenSearchAnnouncements_thenGuidanceWithoutRepoHit() {
        when(subscriptionRepository.findByUserId(7L)).thenReturn(Optional.empty());

        var result = service.searchAnnouncements(7L, announcementFilter(AnnouncementScope.SUBSCRIPTION, null));

        assertThat(result.items()).isEmpty();
        assertThat(result.scopeMessage()).contains("未设置订阅");
        verifyNoInteractions(announcementRepository);
    }

    @Test
    @DisplayName("给定 scope=holdings，when检索，then hook 全用户列表按 userId 过滤后命中")
    void givenHoldingsScope_whenSearchAnnouncements_thenHookFilteredByUser() {
        when(subscriptionHook.activePositionTargets()).thenReturn(List.of(
                new IntelligenceTarget(7L, 101L, "600519", "贵州茅台"),
                new IntelligenceTarget(8L, 201L, "000001", "平安银行"),
                new IntelligenceTarget(7L, 102L, "300750", "宁德时代")));
        when(announcementRepository.search(any())).thenReturn(new PageResult<>(List.of(
                announcement("600519", "茅台公告", null, null),
                announcement("000001", "他人持仓公告", null, null),
                announcement("300750", "宁德公告", null, null)), 30, 1, 10));

        var result = service.searchAnnouncements(7L, announcementFilter(AnnouncementScope.HOLDINGS, null));

        assertThat(result.items()).extracting(v -> v.title())
                .containsExactly("茅台公告", "宁德公告");
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.scopeMessage()).isNull();
    }

    @Test
    @DisplayName("给定 scope=holdings 且 hook 无本人项目，when检索，then 引导语且不打仓库")
    void givenHoldingsScopeEmpty_whenSearchAnnouncements_thenGuidanceWithoutRepoHit() {
        when(subscriptionHook.activePositionTargets()).thenReturn(List.of(
                new IntelligenceTarget(8L, 201L, "000001", "平安银行")));

        var result = service.searchAnnouncements(7L, announcementFilter(AnnouncementScope.HOLDINGS, null));

        assertThat(result.items()).isEmpty();
        assertThat(result.scopeMessage()).contains("当前无建仓持仓阶段项目");
        verifyNoInteractions(announcementRepository);
    }

    @Test
    @DisplayName("给定 stock 不在订阅标的集，when检索，then 空结果+范围外话术且不打仓库")
    void givenStockOutOfScope_whenSearchAnnouncements_thenOutOfRangeWithoutRepoHit() {
        stubSubscription("600519");

        var result = service.searchAnnouncements(7L,
                announcementFilter(AnnouncementScope.SUBSCRIPTION, "000001"));

        assertThat(result.items()).isEmpty();
        assertThat(result.scopeMessage()).contains("不在").contains("订阅");
        verifyNoInteractions(announcementRepository);
    }

    // ===== fix round 1（Important #1）：scope 路径本页超采 + 残余护栏话术 =====

    @Test
    @DisplayName("给定 scope 路径，when检索，then 本页超采：pageSize=min(100, max(20, limit×10)) 而非 limit")
    void givenScopePath_whenSearchAnnouncements_thenPageOverfetched() {
        stubSubscription("600519");
        when(announcementRepository.search(any())).thenReturn(new PageResult<>(List.of(), 0, 1, 100));

        service.searchAnnouncements(7L, new AnnouncementSearchFilter(
                null, null, null, null, null, AnnouncementScope.SUBSCRIPTION, null)); // limit 缺省 10 → 100
        service.searchAnnouncements(7L, new AnnouncementSearchFilter(
                null, null, null, null, null, AnnouncementScope.SUBSCRIPTION, 2));    // 2×10=20
        service.searchAnnouncements(7L, new AnnouncementSearchFilter(
                null, null, null, null, null, AnnouncementScope.SUBSCRIPTION, 5));    // 5×10=50

        verifyAnnouncementPage(1, 100);
        verifyAnnouncementPage(1, 20);
        verifyAnnouncementPage(1, 50);
    }

    @Test
    @DisplayName("给定超采页混入 scope 外条目，when limit=3 检索，then 裁回前 3 条且顺序保持")
    void givenOverfetchedPage_whenSearchAnnouncements_thenTruncatedToLimitInOrder() {
        stubSubscription("600519");
        // 12 条交替：偶位 scope 内（600519）、奇位 scope 外（000001）——顺序即仓库 published_at 倒序
        List<AnnouncementRecord> mixed = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            mixed.add(announcement(i % 2 == 0 ? "600519" : "000001", "公告" + i, null, null));
        }
        when(announcementRepository.search(any())).thenReturn(new PageResult<>(mixed, 12, 1, 100));

        var result = service.searchAnnouncements(7L, new AnnouncementSearchFilter(
                null, null, null, null, null, AnnouncementScope.SUBSCRIPTION, 3));

        assertThat(result.items()).extracting(v -> v.title())
                .containsExactly("公告0", "公告2", "公告4"); // 前 3 条 scope 内、倒序保持
        assertThat(result.total()).isEqualTo(3);
        assertThat(result.scopeMessage()).isNull();
    }

    @Test
    @DisplayName("给定超采页全市场刷满 scope 外且 total>0，when检索，then 残余护栏专属话术而非假空「检索无结果」")
    void givenOverfetchedPageAllOutOfScope_whenSearchAnnouncements_thenGuardMessageNotFalseEmpty() {
        stubSubscription("600519");
        when(announcementRepository.search(any())).thenReturn(new PageResult<>(
                List.of(announcement("000001", "全市场major刷满", null, null)), 100, 1, 100));

        var subscription = service.searchAnnouncements(7L,
                announcementFilter(AnnouncementScope.SUBSCRIPTION, null));

        assertThat(subscription.items()).isEmpty();
        assertThat(subscription.scopeMessage()).contains("未命中").contains("订阅");

        when(subscriptionHook.activePositionTargets()).thenReturn(List.of(
                new IntelligenceTarget(7L, 101L, "600519", "贵州茅台")));
        var holdings = service.searchAnnouncements(7L,
                announcementFilter(AnnouncementScope.HOLDINGS, null));

        assertThat(holdings.items()).isEmpty();
        assertThat(holdings.scopeMessage()).contains("未命中").contains("持仓");
    }

    @Test
    @DisplayName("给定 scope 字符串变体，when解析，then null/空白/未知归 ALL（F09 缺省不受限）")
    void givenScopeStrings_whenParse_thenLenientToAll() {
        assertThat(AnnouncementScope.parse(null)).isEqualTo(AnnouncementScope.ALL);
        assertThat(AnnouncementScope.parse(" ")).isEqualTo(AnnouncementScope.ALL);
        assertThat(AnnouncementScope.parse("mine")).isEqualTo(AnnouncementScope.ALL);
        assertThat(AnnouncementScope.parse(" subscription ")).isEqualTo(AnnouncementScope.SUBSCRIPTION);
        assertThat(AnnouncementScope.parse("holdings")).isEqualTo(AnnouncementScope.HOLDINGS);
    }

    @Test
    @DisplayName("给定混合抽取态公告，when检索，then 条目八字段直传、annTypes null 归一空数组")
    void givenMixedRecords_whenSearchAnnouncements_thenViewMapsFields() {
        AnnouncementMetrics metrics = new AnnouncementMetrics(new BigDecimal("128.56"), null,
                null, null, null, "每10股派2元", List.of("归母净利润"));
        when(announcementRepository.search(any())).thenReturn(new PageResult<>(List.of(
                announcement("600519", "茅台半年报", metrics, List.of(AnnouncementType.PERIODIC_REPORT)),
                announcement("000001", "无抽取公告", null, null)), 2, 1, 10));

        var result = service.searchAnnouncements(7L, announcementFilter(null, null));

        var view = result.items().get(0);
        assertThat(view.title()).isEqualTo("茅台半年报");
        assertThat(view.stockCode()).isEqualTo("600519");
        assertThat(view.stockName()).isEqualTo("标的600519");
        assertThat(view.annTypes()).containsExactly(AnnouncementType.PERIODIC_REPORT);
        assertThat(view.annTypeSource()).isEqualTo("公司公告");
        assertThat(view.metrics()).isSameAs(metrics);
        assertThat(view.pdfUrl()).isEqualTo("https://x/600519.pdf");
        assertThat(view.publishedAt()).isEqualTo(Instant.parse("2026-09-28T13:00:00Z"));
        var noExtract = result.items().get(1);
        assertThat(noExtract.annTypes()).isEmpty();
        assertThat(noExtract.metrics()).isNull();
    }

    /** 断言公告仓库收到 page/pageSize 恰为期望值的 PageQuery（其余字段不限）。 */
    private void verifyAnnouncementPage(int page, int pageSize) {
        verify(announcementRepository, org.mockito.Mockito.times(1)).search(argThat(q ->
                q.page() == page && q.pageSize() == pageSize));
    }

    // ===== MS-22 Task 6：macroBrief（指标节 + 政策节 + 缺失显式）=====

    /** 宏观观测点（series/latest 共用形态，value/yoy 同值简化）。 */
    private static MacroPoint macroPoint(String indicator, String period, String periodType, String value) {
        return new MacroPoint(indicator, period, periodType, new BigDecimal(value), null, null, null);
    }

    /** 政策事件合并视图（isPolicy 由哨兵 summary 派生——sentinel=false 行走 {@link PolicyEvent#of} 工厂）。 */
    private static PolicyEvent policyEvent(long id, String title, PolicyDirection direction,
            PolicyStrength strength, String summary) {
        return PolicyEvent.of(id, id, "pboc", "ext-" + id, title, "https://x/p" + id,
                Instant.parse("2026-09-28T09:30:00Z"), direction, strength,
                List.of("房地产", "基建"), summary, PolicyConfidence.HIGH,
                ExtractStatus.SUCCESS, "deepseek-chat", Instant.parse("2026-09-28T10:00:00Z"));
    }

    @Test
    @DisplayName("给定缺省过滤器，when宏观简报，then全七指标各取 latest+series(5) 与近30天政策窗口")
    void givenDefaultFilter_whenMacroBrief_thenAllIndicatorsAndThirtyDayWindow() {
        when(macroQueryService.latest()).thenReturn(List.of(
                macroPoint("CPI", "2026-09", "MONTH", "0.6"),
                macroPoint("PPI", "2026-09", "MONTH", "-0.8"),
                macroPoint("PMI", "2026-09", "MONTH", "50.4"),
                macroPoint("LPR", "2026-09", "MONTH", "3.0"),
                macroPoint("AFMI", "2026-09", "MONTH", "102.1"),
                macroPoint(MacroPoint.INDICATOR_TY1Y, "2026-09-30", "DAY", "1.45"),
                macroPoint(MacroPoint.INDICATOR_TY10Y, "2026-09-30", "DAY", "1.75")));

        var result = service.macroBrief(new MacroBriefFilter(null, null));

        // 全七指标缺省序（五先行 + TY1Y/TY10Y），每指标 series 各取前 5 期（最新在前）
        verify(macroQueryService).latest();
        for (String ind : List.of("CPI", "PPI", "PMI", "LPR", "AFMI",
                MacroPoint.INDICATOR_TY1Y, MacroPoint.INDICATOR_TY10Y)) {
            verify(macroQueryService).series(ind, 5);
        }
        assertThat(result.indicators()).extracting(v -> v.indicator())
                .containsExactly("CPI", "PPI", "PMI", "LPR", "AFMI",
                        MacroPoint.INDICATOR_TY1Y, MacroPoint.INDICATOR_TY10Y);
        assertThat(result.missing()).isEmpty();
        assertThat(result.generatedAt()).isEqualTo(FIXED_CLOCK.instant());
        // 政策窗口：近 30 天闭区间含今日（2026-09-04..2026-10-03），direction 不过滤，pageSize 20
        verify(policyRepository).searchEvents(argThat(q -> q.page() == 1 && q.pageSize() == 20
                && LocalDate.of(2026, 9, 4).equals(q.from())
                && LocalDate.of(2026, 10, 3).equals(q.to())
                && q.direction() == null && q.keyword() == null));
    }

    @Test
    @DisplayName("给定指标串变体，when解析，then trim+大写+去重保序；空白归全七缺省")
    void givenIndicatorVariants_whenMacroBrief_thenNormalizedDedupedOrDefaults() {
        var result = service.macroBrief(new MacroBriefFilter(" cpi,PMI ,CPI ", null));
        assertThat(result.missing()).as("归一（trim+大写）去重后按请求序").containsExactly("CPI", "PMI");

        var blank = service.macroBrief(new MacroBriefFilter("  ", null));
        assertThat(blank.missing()).as("空白归全七缺省").containsExactly(
                "CPI", "PPI", "PMI", "LPR", "AFMI", "TY1Y", "TY10Y");
        verify(macroQueryService, org.mockito.Mockito.times(2)).latest(); // 每次调用各取一次 latest
    }

    @Test
    @DisplayName("给定越界 policyDays，when宏观简报，then夹紧 1..90：999→90 天窗、0→单日窗")
    void givenOutOfRangePolicyDays_whenMacroBrief_thenClampedWindow() {
        service.macroBrief(new MacroBriefFilter(null, 999)); // 90 天窗：2026-07-06..2026-10-03
        verify(policyRepository).searchEvents(argThat(q ->
                LocalDate.of(2026, 7, 6).equals(q.from()) && LocalDate.of(2026, 10, 3).equals(q.to())));

        service.macroBrief(new MacroBriefFilter(null, 0)); // 1 天窗：from=to=今日
        verify(policyRepository).searchEvents(argThat(q ->
                LocalDate.of(2026, 10, 3).equals(q.from()) && LocalDate.of(2026, 10, 3).equals(q.to())));
    }

    @Test
    @DisplayName("给定部分指标无数据，when宏观简报，then缺失显式列出且不取 series（F13 不编造不省略）")
    void givenMissingIndicators_whenMacroBrief_thenExplicitMissingWithoutSeriesCall() {
        when(macroQueryService.latest()).thenReturn(List.of(
                macroPoint("CPI", "2026-09", "MONTH", "0.6"),
                macroPoint(MacroPoint.INDICATOR_TY1Y, "2026-09-30", "DAY", "1.45")));
        when(macroQueryService.series(eq("CPI"), anyInt())).thenReturn(List.of(
                macroPoint("CPI", "2026-09", "MONTH", "0.6"),
                macroPoint("CPI", "2026-08", "MONTH", "0.5")));

        var result = service.macroBrief(new MacroBriefFilter("CPI,PMI,TY1Y,GDP", null));

        // 缺失（库中无 latest）与未知指标码同显式列出，请求序保持
        assertThat(result.missing()).containsExactly("PMI", "GDP");
        assertThat(result.indicators()).hasSize(2);
        var cpi = result.indicators().get(0);
        assertThat(cpi.value()).isEqualByComparingTo("0.6");
        assertThat(cpi.period()).isEqualTo("2026-09");
        assertThat(cpi.periodType()).isEqualTo("MONTH");
        assertThat(cpi.series()).extracting(IntelligenceQueryService.SeriesPoint::period)
                .as("序列 period 倒序——最新在前").containsExactly("2026-09", "2026-08");
        var ty = result.indicators().get(1);
        assertThat(ty.series()).as("TY 无历史序列原样空列表透传（工具层翻译 note）").isEmpty();
        // 仅命中指标取 series，缺失指标不查（也无从查起）
        verify(macroQueryService).series("CPI", 5);
        verify(macroQueryService).series(MacroPoint.INDICATOR_TY1Y, 5);
        verify(macroQueryService, never()).series(eq("PMI"), anyInt());
        verify(macroQueryService, never()).series(eq("GDP"), anyInt());
    }

    @Test
    @DisplayName("给定含非政策兜底行的检索页，when宏观简报，then九字段映射且 isPolicy=false 标记保留")
    void givenPolicyPageWithFallbackRow_whenMacroBrief_thenViewMapsFieldsWithMarker() {
        when(policyRepository.searchEvents(any())).thenReturn(new PageResult<>(List.of(
                policyEvent(1L, "央行降准", PolicyDirection.EASING, PolicyStrength.HIGH, "降准 0.5 个百分点"),
                policyEvent(2L, "领导活动新闻", PolicyDirection.NEUTRAL, PolicyStrength.LOW,
                        PolicyEvent.NON_POLICY_SUMMARY)), 5, 1, 20));

        var result = service.macroBrief(new MacroBriefFilter("CPI", null));

        assertThat(result.total()).isEqualTo(5);
        assertThat(result.policies()).hasSize(2);
        var first = result.policies().get(0);
        assertThat(first.title()).isEqualTo("央行降准");
        assertThat(first.direction()).isEqualTo(PolicyDirection.EASING);
        assertThat(first.strength()).isEqualTo(PolicyStrength.HIGH);
        assertThat(first.areas()).containsExactly("房地产", "基建");
        assertThat(first.summary()).isEqualTo("降准 0.5 个百分点");
        assertThat(first.confidence()).isEqualTo(PolicyConfidence.HIGH);
        assertThat(first.isPolicy()).isTrue();
        assertThat(first.url()).isEqualTo("https://x/p1");
        assertThat(first.publishedAt()).isEqualTo(Instant.parse("2026-09-28T09:30:00Z"));
        var fallback = result.policies().get(1);
        assertThat(fallback.isPolicy()).as("非政策兜底行也返回但带 isPolicy=false 标记").isFalse();
        assertThat(fallback.confidence()).isEqualTo(PolicyConfidence.HIGH);
    }
}
