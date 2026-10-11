package com.portfolio.invest.application.research;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.agent.InvestTools;
import com.portfolio.invest.agent.report.ReportAnchorRechecker;
import com.portfolio.invest.application.cache.ApplicationCache;
import com.portfolio.invest.application.intelligence.AnnouncementSearchFilter;
import com.portfolio.invest.application.intelligence.IntelligenceQueryService;
import com.portfolio.invest.application.wiki.WikiApplicationService;
import com.portfolio.invest.application.wiki.WikiEntryView;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.research.ReportAnchor;
import com.portfolio.invest.domain.research.ReportAnchorRepository;
import com.portfolio.invest.domain.wiki.WikiEntryType;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolEmitter;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 研报假设过期重查单测（MS-32 B12，设计规格 §3.8，内存假件 + 固定时钟）：
 * 四态判定（FRESH 现值再现 / CHANGED 超 2% 漂移 / STALE asOf 超线（CHANGED 优先并存）/
 * UNVERIFIABLE 白名单外·工具异常·失败信封·args 错型·锚定片段无数据性数字）、
 * 容差边界恰 2% 含端、TTL 缓存防抖（命中不重查不重写 / miss 全量写一次）、
 * 直调 emitter no-op 安全（get_kline 不 NPE）、wiki 经 userId 轻实例直查且
 * GENERATED 无数据时点语义不判 STALE（决策 #17）。
 * fake InvestTools 子类覆写工具方法（与 Toolkit 注册实例共享同一直调面）。
 */
class ReportStalenessServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant NOW = Instant.parse("2026-10-11T04:00:00Z"); // 上海 12:00
    /** 行情族 stale 线 1 天：3 个自然日前必超线。 */
    private static final Instant THREE_DAYS_AGO = Instant.parse("2026-10-08T04:00:00Z");
    private static final Long USER = 42L;
    private static final Long REPORT = 9L;

    private final FakeAnchorRepository anchors = new FakeAnchorRepository();
    private final FakeApplicationCache cache = new FakeApplicationCache();
    private final FakeInvestTools investTools = new FakeInvestTools();
    private final FakeWikiService wiki = new FakeWikiService();
    private final FakeIntelligenceService intelligence = new FakeIntelligenceService();

    /** 显式钉死口径：容差 2% / 重查 TTL 5m；trust stale 线取默认 1/110/35 天。 */
    private static InvestProperties props() {
        InvestProperties props = new InvestProperties();
        props.getReport().setStaleness(new InvestProperties.Report.Staleness(
                Duration.ofMinutes(5), new BigDecimal("0.02")));
        return props;
    }

    /** 未用到的依赖传 null（单锚点派发只触对应工具，null 不会被触碰）。 */
    private ReportStalenessService service() {
        ReportAnchorRechecker rechecker = new ReportAnchorRechecker(
                investTools, null, null, intelligence, wiki, new ObjectMapper(), props(), Clock.fixed(NOW, ZONE));
        return new ReportStalenessService(anchors, cache, rechecker, props());
    }

    private static ReportAnchor anchor(long id, String snippet, String toolName,
                                       Map<String, Object> args, Instant asOf, String asOfKind) {
        return new ReportAnchor(id, REPORT, snippet, 1, "VERIFIED", toolName, args, asOf, asOfKind, "raw");
    }

    /** 现价可控的行情 JSON（其余字段远离锚定值域，避免误入容差；time/代码形态由提取器天然排除）。 */
    private static String quoteJson(double price) {
        return "{\"code\":\"600519\",\"name\":\"贵州茅台\",\"price\":" + price + ",\"change\":5.0,"
                + "\"changePct\":0.3,\"volume\":12345,\"amount\":9876543210,\"pe\":35.2,\"pb\":9.8,"
                + "\"time\":\"2026-10-11 15:00:00\"}";
    }

    @Test
    @DisplayName("给定现值含锚定值（get_quote），when statusesFor，then FRESH 且 currentValue 为现值")
    void givenCurrentValueContainsAnchored_whenStatusesFor_thenFresh() {
        investTools.quoteJson = quoteJson(1520.33);
        anchors.seed(REPORT, anchor(1L, "1520.33元", "get_quote",
                Map.of("code", "600519"), NOW, "DATA"));

        List<ReportStalenessService.AnchorStatus> statuses = service().statusesFor(USER, REPORT);

        assertThat(statuses).singleElement().satisfies(s -> {
            assertThat(s.anchorId()).isEqualTo(1L);
            assertThat(s.state()).isEqualTo(ReportStalenessService.AnchorState.FRESH);
            assertThat(s.currentValue()).isEqualTo("1520.33");
        });
    }

    @Test
    @DisplayName("给定现值漂移超 2% 容差，when statusesFor，then CHANGED 且 currentValue 为最近现值")
    void givenCurrentValueDrifted_whenStatusesFor_thenChanged() {
        investTools.quoteJson = quoteJson(1700.0);
        anchors.seed(REPORT, anchor(1L, "1520.33元", "get_quote",
                Map.of("code", "600519"), NOW, "DATA"));

        List<ReportStalenessService.AnchorStatus> statuses = service().statusesFor(USER, REPORT);

        assertThat(statuses).singleElement().satisfies(s -> {
            assertThat(s.state()).isEqualTo(ReportStalenessService.AnchorState.CHANGED);
            assertThat(s.currentValue()).isNotBlank();
        });
    }

    @Test
    @DisplayName("给定漂移恰等 2% 容差线（1000→1020），when statusesFor，then FRESH（含端）")
    void givenDriftExactlyAtTolerance_whenStatusesFor_thenFresh() {
        investTools.quoteJson = quoteJson(1020.0);
        anchors.seed(REPORT, anchor(1L, "1000元", "get_quote",
                Map.of("code", "600519"), NOW, "DATA"));

        List<ReportStalenessService.AnchorStatus> statuses = service().statusesFor(USER, REPORT);

        assertThat(statuses).singleElement()
                .extracting(ReportStalenessService.AnchorStatus::state)
                .isEqualTo(ReportStalenessService.AnchorState.FRESH);
    }

    @Test
    @DisplayName("给定行情锚定 asOf 距今 3 天（超 1 天线）且现值再现，when statusesFor，then STALE")
    void givenQuoteAnchorBeyondStaleLine_whenStatusesFor_thenStale() {
        investTools.quoteJson = quoteJson(1520.33);
        anchors.seed(REPORT, anchor(1L, "1520.33元", "get_quote",
                Map.of("code", "600519"), THREE_DAYS_AGO, "DATA"));

        List<ReportStalenessService.AnchorStatus> statuses = service().statusesFor(USER, REPORT);

        assertThat(statuses).singleElement().satisfies(s -> {
            assertThat(s.state()).isEqualTo(ReportStalenessService.AnchorState.STALE);
            assertThat(s.currentValue()).isEqualTo("1520.33");
        });
    }

    @Test
    @DisplayName("给定现值漂移且 asOf 超线并存，when statusesFor，then CHANGED 优先（不落 STALE）")
    void givenChangedAndStaleCoexist_whenStatusesFor_thenChangedWins() {
        investTools.quoteJson = quoteJson(1700.0);
        anchors.seed(REPORT, anchor(1L, "1520.33元", "get_quote",
                Map.of("code", "600519"), THREE_DAYS_AGO, "DATA"));

        List<ReportStalenessService.AnchorStatus> statuses = service().statusesFor(USER, REPORT);

        assertThat(statuses).singleElement()
                .extracting(ReportStalenessService.AnchorStatus::state)
                .isEqualTo(ReportStalenessService.AnchorState.CHANGED);
    }

    @Test
    @DisplayName("给定白名单外工具（screen_stocks），when statusesFor，then UNVERIFIABLE 且不调工具")
    void givenToolOutsideWhitelist_whenStatusesFor_thenUnverifiableWithoutToolCall() {
        anchors.seed(REPORT, anchor(1L, "15.2元", "screen_stocks", Map.of(), NOW, "DATA"));

        List<ReportStalenessService.AnchorStatus> statuses = service().statusesFor(USER, REPORT);

        assertThat(statuses).singleElement().satisfies(s -> {
            assertThat(s.state()).isEqualTo(ReportStalenessService.AnchorState.UNVERIFIABLE);
            assertThat(s.currentValue()).isNull();
        });
        assertThat(investTools.quoteCalls).as("白名单外不派发").isZero();
    }

    @Test
    @DisplayName("给定工具调用抛异常（装饰器外的裸直调面），when statusesFor，then UNVERIFIABLE（非抛）")
    void givenToolThrows_whenStatusesFor_thenUnverifiable() {
        investTools.throwOnQuote = true;
        anchors.seed(REPORT, anchor(1L, "1520.33元", "get_quote",
                Map.of("code", "600519"), NOW, "DATA"));

        List<ReportStalenessService.AnchorStatus> statuses = service().statusesFor(USER, REPORT);

        assertThat(statuses).singleElement()
                .extracting(ReportStalenessService.AnchorStatus::state)
                .isEqualTo(ReportStalenessService.AnchorState.UNVERIFIABLE);
    }

    @Test
    @DisplayName("给定工具返回失败信封 {\"error\":...}（InvestTools 失败不抛的真实形态），when statusesFor，then UNVERIFIABLE")
    void givenToolReturnsErrorEnvelope_whenStatusesFor_thenUnverifiable() {
        investTools.quoteJson = "{\"error\":\"行情源不可用\",\"hint\":\"请稍后重试\"}";
        anchors.seed(REPORT, anchor(1L, "1520.33元", "get_quote",
                Map.of("code", "600519"), NOW, "DATA"));

        List<ReportStalenessService.AnchorStatus> statuses = service().statusesFor(USER, REPORT);

        assertThat(statuses).singleElement()
                .extracting(ReportStalenessService.AnchorStatus::state)
                .isEqualTo(ReportStalenessService.AnchorState.UNVERIFIABLE);
    }

    @Test
    @DisplayName("给定 args 错型（code 非字符串），when statusesFor，then UNVERIFIABLE 且不调工具")
    void givenArgsWrongType_whenStatusesFor_thenUnverifiableWithoutToolCall() {
        anchors.seed(REPORT, anchor(1L, "1520.33元", "get_quote",
                Map.of("code", 600519), NOW, "DATA"));

        List<ReportStalenessService.AnchorStatus> statuses = service().statusesFor(USER, REPORT);

        assertThat(statuses).singleElement()
                .extracting(ReportStalenessService.AnchorStatus::state)
                .isEqualTo(ReportStalenessService.AnchorState.UNVERIFIABLE);
        assertThat(investTools.quoteCalls).as("错型在派发前拦截").isZero();
    }

    @Test
    @DisplayName("给定锚定片段无数据性数字（纯序号），when statusesFor，then UNVERIFIABLE（无从比对）")
    void givenSnippetWithoutDataLikeNumber_whenStatusesFor_thenUnverifiable() {
        anchors.seed(REPORT, anchor(1L, "第1条", "get_quote",
                Map.of("code", "600519"), NOW, "DATA"));

        List<ReportStalenessService.AnchorStatus> statuses = service().statusesFor(USER, REPORT);

        assertThat(statuses).singleElement()
                .extracting(ReportStalenessService.AnchorStatus::state)
                .isEqualTo(ReportStalenessService.AnchorState.UNVERIFIABLE);
    }

    @Test
    @DisplayName("给定缓存命中，when statusesFor，then 直返缓存值不重查不重写")
    void givenCacheHit_whenStatusesFor_thenReturnsCachedWithoutRecheck() {
        ReportStalenessService.AnchorStatus cached =
                new ReportStalenessService.AnchorStatus(1L, ReportStalenessService.AnchorState.STALE, "1.0");
        cache.seed(ReportStalenessService.CACHE_KEY_PREFIX + REPORT, List.of(cached));
        anchors.seed(REPORT, anchor(1L, "1520.33元", "get_quote",
                Map.of("code", "600519"), THREE_DAYS_AGO, "DATA"));

        List<ReportStalenessService.AnchorStatus> statuses = service().statusesFor(USER, REPORT);

        assertThat(statuses).containsExactly(cached);
        assertThat(investTools.quoteCalls).as("命中不重查").isZero();
        assertThat(cache.puts).as("命中不重写").isZero();
    }

    @Test
    @DisplayName("给定缓存 miss，when 首次重查，then 全量按 TTL=5m 写缓存一次且二次调用命中")
    void givenCacheMiss_whenStatusesFor_thenPutsOnceWithTtlAndSecondCallHits() {
        investTools.quoteJson = quoteJson(1520.33);
        anchors.seed(REPORT,
                anchor(1L, "1520.33元", "get_quote", Map.of("code", "600519"), NOW, "DATA"),
                anchor(2L, "35.2元", "get_quote", Map.of("code", "600519"), NOW, "DATA"));
        ReportStalenessService service = service();

        List<ReportStalenessService.AnchorStatus> first = service.statusesFor(USER, REPORT);
        List<ReportStalenessService.AnchorStatus> second = service.statusesFor(USER, REPORT);

        assertThat(first).hasSize(2);
        assertThat(second).as("二次调用命中同一缓存实例").isSameAs(first);
        assertThat(cache.puts).as("miss 后恰写一次").isEqualTo(1);
        assertThat(cache.ttls.get(ReportStalenessService.CACHE_KEY_PREFIX + REPORT))
                .isEqualTo(Duration.ofMinutes(5));
        assertThat(investTools.quoteCalls).as("两锚点各查一次后不再重查").isEqualTo(2);
    }

    @Test
    @DisplayName("给定 wiki 锚定（GENERATED 超线 + 现值笔记再现），when statusesFor，then FRESH 且 userId 经轻实例直查不判 STALE")
    void givenWikiAnchorGeneratedBeyondLine_whenStatusesFor_thenFreshWithUserIdPassed() {
        wiki.entries = List.of(new WikiEntryView(1L, WikiEntryType.BOOK_NOTE, "安全边际",
                "买入须留足 20% 的安全边际", null, null, null, null));
        anchors.seed(REPORT, anchor(1L, "20%", "search_wiki",
                Map.of("keyword", "安全边际"), THREE_DAYS_AGO, "GENERATED"));

        List<ReportStalenessService.AnchorStatus> statuses = service().statusesFor(USER, REPORT);

        assertThat(statuses).singleElement().satisfies(s -> {
            assertThat(s.state()).isEqualTo(ReportStalenessService.AnchorState.FRESH);
            assertThat(s.currentValue()).isEqualTo("20");
        });
        assertThat(wiki.calls).isEqualTo(1);
        assertThat(wiki.lastUserId).as("用户态工具经 userId 直查").isEqualTo(USER);
    }

    @Test
    @DisplayName("给定 K 线锚定，when statusesFor，then 直调 no-op emitter 不 NPE 且摘要现值 FRESH")
    void givenKlineAnchor_whenStatusesFor_thenFreshViaDirectCallWithNoopEmitter() {
        investTools.klineSummary = "600519 近120根日K：最新收盘 30.5，区间高点 30.8、低点 29.9。";
        anchors.seed(REPORT, anchor(1L, "30.5元", "get_kline",
                Map.of("code", "600519", "period", "day", "limit", 120), NOW, "DATA"));

        List<ReportStalenessService.AnchorStatus> statuses = service().statusesFor(USER, REPORT);

        assertThat(statuses).singleElement().satisfies(s -> {
            assertThat(s.state()).isEqualTo(ReportStalenessService.AnchorState.FRESH);
            assertThat(s.currentValue()).isEqualTo("30.5");
        });
        assertThat(investTools.klineCalls).isEqualTo(1);
    }

    @Test
    @DisplayName("给定财报锚定（get_financials），when statusesFor，then 摘要现值 FRESH（财报族 60 天未超 110 线）")
    void givenFinancialsAnchor_whenStatusesFor_thenFresh() {
        investTools.toolText = "贵州茅台最新报告期：每股收益 15.2元，加权ROE 30.5%。";
        anchors.seed(REPORT, anchor(1L, "15.2元", "get_financials",
                Map.of("code", "600519"), Instant.parse("2026-08-12T04:00:00Z"), "DATA"));

        List<ReportStalenessService.AnchorStatus> statuses = service().statusesFor(USER, REPORT);

        assertThat(statuses).singleElement()
                .extracting(ReportStalenessService.AnchorStatus::state)
                .isEqualTo(ReportStalenessService.AnchorState.FRESH);
    }

    @Test
    @DisplayName("给定大盘锚定（get_market_overview），when statusesFor，then 摘要现值 FRESH")
    void givenOverviewAnchor_whenStatusesFor_thenFresh() {
        investTools.toolText = "上证指数报 3000.5 点，涨跌幅 0.8%。";
        anchors.seed(REPORT, anchor(1L, "3000.5点", "get_market_overview", Map.of(), NOW, "DATA"));

        List<ReportStalenessService.AnchorStatus> statuses = service().statusesFor(USER, REPORT);

        assertThat(statuses).singleElement().satisfies(s -> {
            assertThat(s.state()).isEqualTo(ReportStalenessService.AnchorState.FRESH);
            assertThat(s.currentValue()).isEqualTo("3000.5");
        });
    }

    @Test
    @DisplayName("给定估值锚定（get_valuation），when statusesFor，then 摘要现值 FRESH")
    void givenValuationAnchor_whenStatusesFor_thenFresh() {
        investTools.toolText = "全A PE 中位数 25.3，历史分位 62%。";
        anchors.seed(REPORT, anchor(1L, "25.3", "get_valuation", Map.of(), NOW, "DATA"));

        List<ReportStalenessService.AnchorStatus> statuses = service().statusesFor(USER, REPORT);

        assertThat(statuses).singleElement()
                .extracting(ReportStalenessService.AnchorStatus::state)
                .isEqualTo(ReportStalenessService.AnchorState.FRESH);
    }

    @Test
    @DisplayName("给定杜邦锚定（analyze_financials），when statusesFor，then 摘要现值 FRESH")
    void givenAnalyzeFinancialsAnchor_whenStatusesFor_thenFresh() {
        investTools.toolText = "近12季杜邦拆解：净利率 52.1，总资产周转率 0.5。";
        anchors.seed(REPORT, anchor(1L, "52.1", "analyze_financials",
                Map.of("code", "600519"), NOW, "DATA"));

        List<ReportStalenessService.AnchorStatus> statuses = service().statusesFor(USER, REPORT);

        assertThat(statuses).singleElement()
                .extracting(ReportStalenessService.AnchorStatus::state)
                .isEqualTo(ReportStalenessService.AnchorState.FRESH);
    }

    @Test
    @DisplayName("给定行业锚定（analyze_industry），when statusesFor，then 摘要现值 FRESH")
    void givenAnalyzeIndustryAnchor_whenStatusesFor_thenFresh() {
        investTools.toolText = "银行行业 PE 6.2，处于历史 35% 分位。";
        anchors.seed(REPORT, anchor(1L, "6.2", "analyze_industry",
                Map.of("industryCode", "801780"), NOW, "DATA"));

        List<ReportStalenessService.AnchorStatus> statuses = service().statusesFor(USER, REPORT);

        assertThat(statuses).singleElement()
                .extracting(ReportStalenessService.AnchorStatus::state)
                .isEqualTo(ReportStalenessService.AnchorState.FRESH);
    }

    @Test
    @DisplayName("给定宏观锚定 asOf 距今 40 天（超 35 天线）且现值再现，when statusesFor，then STALE（宏观族）")
    void givenMacroAnchorBeyondStaleLine_whenStatusesFor_thenStale() {
        investTools.toolText = "2026年9月 CPI 同比 102.3，LPR 3.5%。";
        anchors.seed(REPORT, anchor(1L, "102.3", "macro_brief",
                Map.of("indicators", "CPI"), Instant.parse("2026-09-01T04:00:00Z"), "DATA"));

        List<ReportStalenessService.AnchorStatus> statuses = service().statusesFor(USER, REPORT);

        assertThat(statuses).singleElement()
                .extracting(ReportStalenessService.AnchorStatus::state)
                .isEqualTo(ReportStalenessService.AnchorState.STALE);
    }

    @Test
    @DisplayName("给定新闻锚定（search_news），when statusesFor，then 条目摘要现值 FRESH")
    void givenNewsAnchor_whenStatusesFor_thenFresh() {
        investTools.toolText = "{\"items\":[{\"summary\":\"茅台三季报净利同比增 20%\"}],\"total\":1}";
        anchors.seed(REPORT, anchor(1L, "20%", "search_news",
                Map.of("q", "茅台"), NOW, "DATA"));

        List<ReportStalenessService.AnchorStatus> statuses = service().statusesFor(USER, REPORT);

        assertThat(statuses).singleElement()
                .extracting(ReportStalenessService.AnchorStatus::state)
                .isEqualTo(ReportStalenessService.AnchorState.FRESH);
    }

    @Test
    @DisplayName("给定公告锚定（search_announcements），when statusesFor，then 经 userId 轻实例直查现值 FRESH")
    void givenAnnouncementAnchor_whenStatusesFor_thenFreshWithUserIdPassed() {
        intelligence.items = List.of(new IntelligenceQueryService.AnnouncementItemView(
                "回购2.5亿元暨增持公告", "600519", "贵州茅台", List.of(), null, null, null, null));
        anchors.seed(REPORT, anchor(1L, "2.5亿元", "search_announcements",
                Map.of("stock", "600519", "limit", 10), NOW, "DATA"));

        List<ReportStalenessService.AnchorStatus> statuses = service().statusesFor(USER, REPORT);

        assertThat(statuses).singleElement()
                .extracting(ReportStalenessService.AnchorStatus::state)
                .isEqualTo(ReportStalenessService.AnchorState.FRESH);
        assertThat(intelligence.lastUserId).as("公告检索经 userId 直查").isEqualTo(USER);
    }

    /** 内存假锚点仓库：seed 即按研报可查。 */
    private static final class FakeAnchorRepository implements ReportAnchorRepository {

        private final Map<Long, List<ReportAnchor>> byReport = new HashMap<>();

        @Override
        public void saveAll(Long reportId, List<ReportAnchor> anchors) {
            byReport.put(reportId, List.copyOf(anchors));
        }

        @Override
        public List<ReportAnchor> findByReportId(Long reportId) {
            return byReport.getOrDefault(reportId, List.of());
        }

        void seed(Long reportId, ReportAnchor... list) {
            byReport.put(reportId, List.of(list));
        }
    }

    /** 内存假缓存：记录 put 次数与 TTL（防抖断言用），seed 支持命中路径。 */
    private static final class FakeApplicationCache implements ApplicationCache {

        private final Map<String, Object> store = new HashMap<>();
        private final Map<String, Duration> ttls = new HashMap<>();
        private int puts;

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(String key) {
            return (T) store.get(key);
        }

        @Override
        public void put(String key, Object value, Duration ttl) {
            store.put(key, value);
            ttls.put(key, ttl);
            puts++;
        }

        <T> void seed(String key, T value) {
            store.put(key, value);
        }
    }

    /** 假 InvestTools：子类覆写白名单直调面，quoteCalls/klineCalls 记派发次数；toolText 为摘要族公共现值文本。 */
    private static class FakeInvestTools extends InvestTools {

        private String quoteJson = ReportStalenessServiceTest.quoteJson(1520.33);
        private String klineSummary = "";
        private String toolText = "";
        private boolean throwOnQuote;
        private int quoteCalls;
        private int klineCalls;

        FakeInvestTools() {
            super(null, null, null, null, null, null, null, null);
        }

        @Override
        public String getQuote(String code) {
            quoteCalls++;
            if (throwOnQuote) {
                throw new IllegalStateException("工具执行失败");
            }
            return quoteJson;
        }

        @Override
        public ToolResultBlock getKline(String code, String period, Integer limit, ToolEmitter emitter) {
            klineCalls++;
            return ToolResultBlock.text(klineSummary);
        }

        @Override
        public ToolResultBlock getFinancials(String code, ToolEmitter emitter) {
            return ToolResultBlock.text(toolText);
        }

        @Override
        public ToolResultBlock getMarketOverview(ToolEmitter emitter) {
            return ToolResultBlock.text(toolText);
        }

        @Override
        public ToolResultBlock getValuation(ToolEmitter emitter) {
            return ToolResultBlock.text(toolText);
        }

        @Override
        public ToolResultBlock analyzeFinancials(String code, ToolEmitter emitter) {
            return ToolResultBlock.text(toolText);
        }

        @Override
        public ToolResultBlock analyzeIndustry(String industryCode, ToolEmitter emitter) {
            return ToolResultBlock.text(toolText);
        }

        @Override
        public String macroBrief(String indicators, Integer policyDays) {
            return toolText;
        }

        @Override
        public String searchNews(String q, String stock, String industry, String from, String to,
                                 Integer minImportance, Integer limit) {
            return toolText;
        }
    }

    /** 假情报服务：searchAnnouncements 条目可配，记录 userId 透传（经真实 UserInvestTools 包装调用）。 */
    private static final class FakeIntelligenceService extends IntelligenceQueryService {

        private List<AnnouncementItemView> items = List.of();
        private Long lastUserId;

        FakeIntelligenceService() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public AnnouncementSearchResult searchAnnouncements(Long userId, AnnouncementSearchFilter filter) {
            lastUserId = userId;
            return new AnnouncementSearchResult(items, items.size(), null);
        }
    }

    /** 假 wiki 服务：entries 可配，记录 userId 透传（用户态直查断言用；经真实 UserInvestTools 包装调用）。 */
    private static final class FakeWikiService extends WikiApplicationService {

        private List<WikiEntryView> entries = List.of();
        private int calls;
        private Long lastUserId;

        FakeWikiService() {
            super(null, null, null);
        }

        @Override
        public List<WikiEntryView> entries(Long userId, WikiEntryType type) {
            calls++;
            lastUserId = userId;
            return entries;
        }
    }
}
