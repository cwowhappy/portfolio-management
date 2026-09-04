package com.portfolio.invest.application.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.portfolio.invest.domain.market.Financials;
import com.portfolio.invest.domain.market.KlineBar;
import com.portfolio.invest.domain.market.MarketDataErrorCode;
import com.portfolio.invest.domain.market.MarketDataException;
import com.portfolio.invest.domain.market.MarketDataSource;
import com.portfolio.invest.domain.market.MarketOverview;
import com.portfolio.invest.domain.market.Quote;
import com.portfolio.invest.domain.market.StockHit;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 行情编排：解析、东财主源降级新浪/腾讯、财务估值兜底、入参校验（无缓存）。 */
class OrchestratingMarketDataServiceTest {

    private final MarketDataSource source = mock(MarketDataSource.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private OrchestratingMarketDataService service;

    @BeforeEach
    void setUp() {
        service = new OrchestratingMarketDataService(source);
    }

    private JsonNode fixture(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/" + name)) {
            return mapper.readTree(in);
        }
    }

    private String fixtureText(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private JsonNode json(String s) {
        try { return mapper.readTree(s); } catch (IOException e) { throw new RuntimeException(e); }
    }

    /** 移除 f162/f167 使行情缺失估值字段，触发财务估值兜底。 */
    private JsonNode quoteWithoutValuation() throws IOException {
        ObjectNode root = (ObjectNode) fixture("eastmoney-quote.json");
        ObjectNode data = (ObjectNode) root.get("data");
        data.remove("f162");
        data.remove("f167");
        return root;
    }

    // ———— search ————

    @DisplayName("search空关键词抛INVALID_QUERY")
    @Test
    void searchEmptyKeywordThrowsInvalidQuery() {
        assertThatThrownBy(() -> service.search(null))
                .isInstanceOf(MarketDataException.class).hasMessageContaining("搜索关键词不能为空");
        assertThatThrownBy(() -> service.search("   "))
                .isInstanceOf(MarketDataException.class).hasMessageContaining("搜索关键词不能为空");
    }

    @DisplayName("search解析结果")
    @Test
    void searchParsesResults() throws IOException {
        when(source.search("茅台")).thenReturn(fixture("eastmoney-search.json"));
        List<StockHit> hits = service.search(" 茅台 ");
        assertThat(hits).isNotEmpty();
        verify(source).search("茅台");
    }

    // ———— quote ————

    @DisplayName("quote主源成功")
    @Test
    void quotePrimarySourceSucceeds() throws IOException {
        when(source.quote("1.600519")).thenReturn(fixture("eastmoney-quote.json"));
        Quote q = service.quote("600519");
        assertThat(q.name()).isEqualTo("贵州茅台");
        assertThat(q.price()).isEqualTo(1415.0);
        assertThat(q.pe()).isEqualTo(21.35);
        assertThat(q.pb()).isEqualTo(7.82);
    }

    @DisplayName("quote主源失败降级新浪")
    @Test
    void quotePrimarySourceFailsFallbackToSina() throws IOException {
        when(source.quote("1.600519"))
                .thenThrow(new MarketDataException(MarketDataErrorCode.UPSTREAM_UNAVAILABLE, "东财挂了"));
        when(source.rawQuote("sh", "600519")).thenReturn(fixtureText("sina-quote.txt"));
        Quote q = service.quote("600519");
        assertThat(q.name()).isEqualTo("贵州茅台");
        assertThat(q.price()).isEqualTo(1415.0);
        verify(source).rawQuote("sh", "600519");
    }

    // ———— kline ————

    @DisplayName("kline周期映射到东财klt")
    @Test
    void klinePeriodMapsToEastmoneyKlt() throws IOException {
        when(source.kline(eq("1.600519"), anyInt(), anyInt())).thenReturn(fixture("eastmoney-kline.json"));
        service.kline("600519", "day", 60);
        service.kline("600519", "week", 60);
        service.kline("600519", "month", 60);
        verify(source).kline("1.600519", 101, 60);
        verify(source).kline("1.600519", 102, 60);
        verify(source).kline("1.600519", 103, 60);
    }

    @DisplayName("kline周期缺省与非法规整")
    @Test
    void klinePeriodDefaultAndInvalidNormalized() throws IOException {
        when(source.kline(eq("1.600519"), anyInt(), anyInt())).thenReturn(fixture("eastmoney-kline.json"));
        service.kline("600519", null, 60);
        verify(source).kline("1.600519", 101, 60);
        // limit<=0 → 120；超过上限 → 500
        service.kline("600519", "day", 0);
        verify(source).kline("1.600519", 101, 120);
        service.kline("600519", "day", 9999);
        verify(source).kline("1.600519", 101, 500);
        assertThatThrownBy(() -> service.kline("600519", "foo", 60))
                .isInstanceOf(MarketDataException.class).hasMessageContaining("period 仅支持");
    }

    @DisplayName("kline主源失败降级腾讯")
    @Test
    void klinePrimarySourceFailsFallbackToTencent() throws IOException {
        when(source.kline("1.600519", 101, 120))
                .thenThrow(new MarketDataException(MarketDataErrorCode.UPSTREAM_UNAVAILABLE, "东财K线挂了"));
        when(source.fallbackKline("sh600519", "day", 120)).thenReturn(fixture("tencent-kline.json"));
        List<KlineBar> bars = service.kline("600519", "day", 120);
        assertThat(bars).hasSize(3);
        assertThat(bars.get(0).date()).isEqualTo("2026-08-14");
        verify(source).fallbackKline("sh600519", "day", 120);
    }

    // ———— financials（估值兜底） ————

    @DisplayName("financials直接使用行情估值")
    @Test
    void financialsUsesQuoteValuationDirectly() throws IOException {
        when(source.quote("1.600519")).thenReturn(fixture("eastmoney-quote.json"));
        when(source.financials("600519.SH")).thenReturn(fixture("eastmoney-financials.json"));
        Financials f = service.financials("600519");
        assertThat(f.pe()).isEqualTo(21.35);
        assertThat(f.pb()).isEqualTo(7.82);
        assertThat(f.indicators()).hasSize(2);
    }

    @DisplayName("financials缺失估值时TTM回算")
    @Test
    void financialsRecalculatesTtmWhenValuationMissing() throws IOException {
        // latest=2026-06-30 eps2 bps10; annual=2025-12-31 eps4; same=2025-06-30 eps1
        when(source.quote("1.600519")).thenReturn(quoteWithoutValuation());
        when(source.financials("600519.SH")).thenReturn(json("""
                {"result":{"data":[
                  {"REPORT_DATE":"2026-06-30 00:00:00","EPSJB":2.0,"BPS":10.0},
                  {"REPORT_DATE":"2025-12-31 00:00:00","EPSJB":4.0,"BPS":null},
                  {"REPORT_DATE":"2025-06-30 00:00:00","EPSJB":1.0,"BPS":9.0}
                ]}}
                """));
        Financials f = service.financials("600519");
        // price=1415 → pb=1415/10=141.5; epsTtm=2+4-1=5 → pe=1415/5=283.0
        assertThat(f.pb()).isEqualTo(141.5);
        assertThat(f.pe()).isEqualTo(283.0);
    }

    @DisplayName("financials最新报告期为年报时直接除EPS")
    @Test
    void financialsLatestAnnualReportDividesEpsDirectly() throws IOException {
        when(source.quote("1.600519")).thenReturn(quoteWithoutValuation());
        when(source.financials("600519.SH")).thenReturn(json("""
                {"result":{"data":[
                  {"REPORT_DATE":"2025-12-31 00:00:00","EPSJB":4.0,"BPS":null},
                  {"REPORT_DATE":"2024-12-31 00:00:00","EPSJB":3.0,"BPS":null}
                ]}}
                """));
        Financials f = service.financials("600519");
        assertThat(f.pe()).isEqualTo(353.75); // 1415/4
        assertThat(f.pb()).isNull();
    }

    @DisplayName("financials年报EPS非正时PE为空")
    @Test
    void financialsAnnualEpsNonPositivePeIsNull() throws IOException {
        // eps=0 旧实现会输出 Infinity，与 TTM 口径对齐：eps<=0 → PE 为 null
        when(source.quote("1.600519")).thenReturn(quoteWithoutValuation());
        when(source.financials("600519.SH")).thenReturn(json("""
                {"result":{"data":[
                  {"REPORT_DATE":"2025-12-31 00:00:00","EPSJB":0.0,"BPS":10.0}
                ]}}
                """));
        Financials f = service.financials("600519");
        assertThat(f.pe()).isNull();
        assertThat(f.pb()).isEqualTo(141.5);
    }

    @DisplayName("financials无年报无同去年数据时估值为空")
    @Test
    void financialsNoAnnualOrSamePeriodLastYearValuationIsNull() throws IOException {
        when(source.quote("1.600519")).thenReturn(quoteWithoutValuation());
        when(source.financials("600519.SH")).thenReturn(json("""
                {"result":{"data":[
                  {"REPORT_DATE":"2026-06-30 00:00:00","EPSJB":2.0,"BPS":10.0}
                ]}}
                """));
        Financials f = service.financials("600519");
        assertThat(f.pe()).isNull();
        assertThat(f.pb()).isEqualTo(141.5);
    }

    @DisplayName("financials指标为空时保持null估值")
    @Test
    void financialsEmptyIndicatorsKeepNullValuation() throws IOException {
        when(source.quote("1.600519")).thenReturn(quoteWithoutValuation());
        when(source.financials("600519.SH")).thenReturn(json("""
                {"result":{"data":[]}}
                """));
        Financials f = service.financials("600519");
        assertThat(f.pe()).isNull();
        assertThat(f.pb()).isNull();
    }

    @DisplayName("financialsTTM为负时不算PE")
    @Test
    void financialsNegativeTtmDoesNotComputePe() throws IOException {
        when(source.quote("1.600519")).thenReturn(quoteWithoutValuation());
        when(source.financials("600519.SH")).thenReturn(json("""
                {"result":{"data":[
                  {"REPORT_DATE":"2026-06-30 00:00:00","EPSJB":-10.0,"BPS":10.0},
                  {"REPORT_DATE":"2025-12-31 00:00:00","EPSJB":4.0,"BPS":null},
                  {"REPORT_DATE":"2025-06-30 00:00:00","EPSJB":1.0,"BPS":9.0}
                ]}}
                """));
        Financials f = service.financials("600519");
        assertThat(f.pe()).isNull();
        assertThat(f.pb()).isEqualTo(141.5);
    }

    @DisplayName("financials缺失同去年数据时不算PE")
    @Test
    void financialsMissingSamePeriodLastYearDoesNotComputePe() throws IOException {
        when(source.quote("1.600519")).thenReturn(quoteWithoutValuation());
        when(source.financials("600519.SH")).thenReturn(json("""
                {"result":{"data":[
                  {"REPORT_DATE":"2026-06-30 00:00:00","EPSJB":2.0,"BPS":10.0},
                  {"REPORT_DATE":"2025-12-31 00:00:00","EPSJB":4.0,"BPS":null}
                ]}}
                """));
        Financials f = service.financials("600519");
        assertThat(f.pe()).isNull();
        assertThat(f.pb()).isEqualTo(141.5);
    }

    @DisplayName("financials畸形报告期不崩溃")
    @Test
    void financialsMalformedReportPeriodDoesNotCrash() throws IOException {
        // latest 报告期为空且存在年报条目：旧实现会 substring(0,4) 抛 StringIndexOutOfBoundsException
        when(source.quote("1.600519")).thenReturn(quoteWithoutValuation());
        when(source.financials("600519.SH")).thenReturn(json("""
                {"result":{"data":[
                  {"REPORT_DATE":"","EPSJB":2.0,"BPS":10.0},
                  {"REPORT_DATE":"2025-12-31 00:00:00","EPSJB":4.0,"BPS":null}
                ]}}
                """));
        Financials f = service.financials("600519");
        assertThat(f.pb()).isEqualTo(141.5); // bps=10 → 1415/10
        assertThat(f.pe()).isNull(); // 报告期畸形，无法回算 TTM
    }

    @DisplayName("financials仅PB缺失时保留行情PE并回算PB")
    @Test
    void financialsOnlyPbMissingKeepsQuotePeAndRecalculatesPb() throws IOException {
        ObjectNode root = (ObjectNode) fixture("eastmoney-quote.json");
        ((ObjectNode) root.get("data")).remove("f167"); // 仅缺 PB，PE=21.35 保留
        when(source.quote("1.600519")).thenReturn(root);
        when(source.financials("600519.SH")).thenReturn(json("""
                {"result":{"data":[
                  {"REPORT_DATE":"2026-06-30 00:00:00","EPSJB":2.0,"BPS":10.0}
                ]}}
                """));

        Financials f = service.financials("600519");

        assertThat(f.pe()).isEqualTo(21.35);  // 行情 PE 保留不回算
        assertThat(f.pb()).isEqualTo(141.5);  // 1415/10
    }

    @DisplayName("financials仅PE缺失时保留行情PB并回算PE")
    @Test
    void financialsOnlyPeMissingKeepsQuotePbAndRecalculatesPe() throws IOException {
        ObjectNode root = (ObjectNode) fixture("eastmoney-quote.json");
        ((ObjectNode) root.get("data")).remove("f162"); // 仅缺 PE，PB=7.82 保留
        when(source.quote("1.600519")).thenReturn(root);
        when(source.financials("600519.SH")).thenReturn(json("""
                {"result":{"data":[
                  {"REPORT_DATE":"2025-12-31 00:00:00","EPSJB":4.0,"BPS":10.0}
                ]}}
                """));

        Financials f = service.financials("600519");

        assertThat(f.pb()).isEqualTo(7.82);    // 行情 PB 保留
        assertThat(f.pe()).isEqualTo(353.75);  // 1415/4
    }

    @DisplayName("financials最新BPS非正时PB为null")
    @Test
    void financialsLatestBpsNonPositivePbIsNull() throws IOException {
        when(source.quote("1.600519")).thenReturn(quoteWithoutValuation());
        when(source.financials("600519.SH")).thenReturn(json("""
                {"result":{"data":[
                  {"REPORT_DATE":"2025-12-31 00:00:00","EPSJB":4.0,"BPS":0.0}
                ]}}
                """));

        Financials f = service.financials("600519");

        assertThat(f.pb()).isNull();           // bps<=0 无意义，避免输出负/零 PB
        assertThat(f.pe()).isEqualTo(353.75);
    }

    @DisplayName("financials最新报告缺EPS时PE为null")
    @Test
    void financialsLatestReportMissingEpsPeIsNull() throws IOException {
        when(source.quote("1.600519")).thenReturn(quoteWithoutValuation());
        when(source.financials("600519.SH")).thenReturn(json("""
                {"result":{"data":[
                  {"REPORT_DATE":"2026-06-30 00:00:00","BPS":10.0},
                  {"REPORT_DATE":"2025-12-31 00:00:00","EPSJB":4.0,"BPS":null}
                ]}}
                """));

        Financials f = service.financials("600519");

        assertThat(f.pe()).isNull();           // 无最新 EPS 无法回算 TTM
        assertThat(f.pb()).isEqualTo(141.5);
    }

    @DisplayName("financials年报缺EPS时不算PE")
    @Test
    void financialsAnnualMissingEpsDoesNotComputePe() throws IOException {
        when(source.quote("1.600519")).thenReturn(quoteWithoutValuation());
        when(source.financials("600519.SH")).thenReturn(json("""
                {"result":{"data":[
                  {"REPORT_DATE":"2026-06-30 00:00:00","EPSJB":2.0,"BPS":10.0},
                  {"REPORT_DATE":"2025-12-31 00:00:00","BPS":null},
                  {"REPORT_DATE":"2025-06-30 00:00:00","EPSJB":1.0,"BPS":9.0}
                ]}}
                """));

        Financials f = service.financials("600519");

        assertThat(f.pe()).isNull();           // 年报 EPS 缺失，TTM 回算缺项
        assertThat(f.pb()).isEqualTo(141.5);
    }

    @DisplayName("financials报告期年份非数字时不算PE")
    @Test
    void financialsReportYearNonNumericDoesNotComputePe() throws IOException {
        // 上游脏数据：年份部分非数字，sameLastYearOf 无法推算去年同期 → 不算 PE
        when(source.quote("1.600519")).thenReturn(quoteWithoutValuation());
        when(source.financials("600519.SH")).thenReturn(json("""
                {"result":{"data":[
                  {"REPORT_DATE":"abcd-06-30 00:00:00","EPSJB":2.0,"BPS":10.0},
                  {"REPORT_DATE":"2025-12-31 00:00:00","EPSJB":4.0,"BPS":null}
                ]}}
                """));

        Financials f = service.financials("600519");

        assertThat(f.pe()).isNull();
        assertThat(f.pb()).isEqualTo(141.5);
    }

    // ———— news ————

    @DisplayName("news股票名为空白时改用代码搜索")
    @Test
    void newsBlankStockNameFallsBackToCodeSearch() throws IOException {
        ObjectNode root = (ObjectNode) fixture("eastmoney-quote.json");
        ((ObjectNode) root.get("data")).put("f58", "  "); // 名称为空白
        when(source.quote("1.600519")).thenReturn(root);
        when(source.news("600519", 10)).thenReturn(fixture("eastmoney-news.json"));

        assertThat(service.news("600519", 10)).isNotEmpty();
        verify(source).news("600519", 10);
    }

    @DisplayName("news使用股票名称搜索并限制条数")
    @Test
    void newsSearchesByStockNameAndLimitsCount() throws IOException {
        when(source.quote("1.600519")).thenReturn(fixture("eastmoney-quote.json"));
        when(source.news("贵州茅台", 10)).thenReturn(fixture("eastmoney-news.json"));
        assertThat(service.news("600519", 10)).isNotEmpty();
        // limit<=0 → 10；limit>20 → 20
        when(source.news("贵州茅台", 20)).thenReturn(fixture("eastmoney-news.json"));
        assertThat(service.news("600519", 25)).isNotEmpty();
        verify(source).news("贵州茅台", 10);
        verify(source).news("贵州茅台", 20);
    }

    @DisplayName("news名称获取失败改用代码搜索")
    @Test
    void newsNameFetchFailsFallsBackToCodeSearch() throws IOException {
        when(source.quote("1.600519"))
                .thenThrow(new MarketDataException(MarketDataErrorCode.UPSTREAM_UNAVAILABLE, "挂了"));
        when(source.rawQuote("sh", "600519"))
                .thenThrow(new MarketDataException(MarketDataErrorCode.UPSTREAM_UNAVAILABLE, "新浪也挂了"));
        when(source.news("600519", 10)).thenReturn(fixture("eastmoney-news.json"));
        assertThat(service.news("600519", 10)).isNotEmpty();
        verify(source).news("600519", 10);
    }

    // ———— overview ————

    @DisplayName("overview主源成功")
    @Test
    void overviewPrimarySourceSucceeds() throws IOException {
        when(source.overview()).thenReturn(fixture("eastmoney-overview.json"));
        MarketOverview o = service.overview();
        assertThat(o.indices()).isNotEmpty();
        verify(source).overview();
    }

    @DisplayName("overview主源失败降级新浪")
    @Test
    void overviewPrimarySourceFailsFallbackToSina() throws IOException {
        when(source.overview()).thenThrow(new MarketDataException(MarketDataErrorCode.UPSTREAM_UNAVAILABLE, "挂了"));
        when(source.rawIndices()).thenReturn(fixtureText("sina-indices.txt"));
        MarketOverview o = service.overview();
        assertThat(o.indices()).hasSize(3);
        verify(source).rawIndices();
    }

    // ———— 探活 ————

    @DisplayName("probeQuoteLatencyMs直连上游")
    @Test
    void probeQuoteLatencyMsHitsUpstreamDirectly() throws IOException {
        when(source.quote("1.600519")).thenReturn(fixture("eastmoney-quote.json"));
        assertThat(service.probeQuoteLatencyMs()).isGreaterThanOrEqualTo(0);
        verify(source).quote("1.600519");
    }

    @DisplayName("probeQuoteLatencyMs用注入时钟测量耗时")
    @Test
    void probeQuoteLatencyMsUsesInjectedClockToMeasure() throws IOException {
        // A3：application 层禁调 System 时钟，探活耗时必须来自注入时钟（可确定性测试）
        AtomicLong now = new AtomicLong(1000);
        Clock fakeClock = new Clock() {
            @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
        };
        OrchestratingMarketDataService clocked = new OrchestratingMarketDataService(source, fakeClock);
        when(source.quote("1.600519")).thenAnswer(inv -> {
            now.addAndGet(120); // 模拟上游耗时 120ms
            return fixture("eastmoney-quote.json");
        });
        assertThat(clocked.probeQuoteLatencyMs()).isEqualTo(120);
    }

    // ———— quoteBatch（NFR：批量取价） ————

    @DisplayName("quoteBatch返回成功标的并跳过失败标的")
    @Test
    void quoteBatchReturnsSucceededAndSkipsFailed() throws IOException {
        when(source.quote("1.600519")).thenReturn(fixture("eastmoney-quote.json"));
        when(source.quote("0.000858"))
                .thenThrow(new MarketDataException(MarketDataErrorCode.UPSTREAM_UNAVAILABLE, "东财挂了"));
        when(source.rawQuote("sz", "000858"))
                .thenThrow(new MarketDataException(MarketDataErrorCode.UPSTREAM_UNAVAILABLE, "新浪也挂了"));

        var result = service.quoteBatch(List.of("600519", "000858"));

        assertThat(result).containsOnlyKeys("600519");
        assertThat(result.get("600519").name()).isEqualTo("贵州茅台");
    }

    @DisplayName("quoteBatch单只失败记warn日志含code")
    @Test
    void quoteBatchSingleFailureLogsWarnWithCode() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(OrchestratingMarketDataService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            when(source.quote("0.000858"))
                    .thenThrow(new MarketDataException(MarketDataErrorCode.UPSTREAM_UNAVAILABLE, "东财挂了"));
            when(source.rawQuote("sz", "000858"))
                    .thenThrow(new MarketDataException(MarketDataErrorCode.UPSTREAM_UNAVAILABLE, "新浪也挂了"));

            assertThat(service.quoteBatch(List.of("000858"))).isEmpty();

            assertThat(appender.list).anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN
                    && e.getFormattedMessage().contains("000858"));
        } finally {
            logger.detachAppender(appender);
        }
    }
}
