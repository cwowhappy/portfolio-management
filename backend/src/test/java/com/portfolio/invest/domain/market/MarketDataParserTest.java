package com.portfolio.invest.domain.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.infrastructure.market.EastmoneyFormat;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * domain 解析器测试（供应商无关）：东财 wire fixture 已平移至
 * {@code infrastructure/market/EastmoneyFormatTest}，本类改以 {@code Raw*} 中间结构构造入参
 * （2026-10-15 L2 下沉），断言语义与下沉前一致；新浪/腾讯文本兜底解析用例维持原状
 * （L2-二期）。
 */
class MarketDataParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static JsonNode quoteJson;
    private static String sinaRaw;
    private static String tencentRaw;
    private static String tencentIndicesRaw;

    @BeforeAll
    static void loadFixtures() throws IOException {
        quoteJson = fixture("eastmoney-quote.json");
        sinaRaw = fixtureText("sina-quote.txt");
        tencentRaw = fixtureText("tencent-quote.txt");
        tencentIndicesRaw = fixtureText("tencent-indices.txt");
    }

    @DisplayName("东财行情中间结构解析出报价")
    @Test
    void givenEastmoneyQuoteRaw_whenParseQuote_thenReturnQuote() {
        Quote q = MarketDataParser.parseQuote(EastmoneyFormat.extractQuote(quoteJson));
        assertThat(q.code()).isEqualTo("600519");
        assertThat(q.name()).isEqualTo("贵州茅台");
        assertThat(q.price()).isEqualTo(1415.0);
        assertThat(q.changePct()).isEqualTo(1.07);
        assertThat(q.pe()).isEqualTo(21.35);
        String expectedTime = LocalDateTime.ofInstant(
                        Instant.ofEpochSecond(1754870400L), ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        assertThat(q.time()).isEqualTo(expectedTime);
    }

    @DisplayName("新浪行情文本解析出报价")
    @Test
    void givenSinaRawText_whenParseSinaQuote_thenReturnQuote() {
        Quote q = MarketDataParser.parseSinaQuote(sinaRaw, "600519");
        assertThat(q.name()).isEqualTo("贵州茅台");
        assertThat(q.price()).isEqualTo(1415.0);
        assertThat(q.prevClose()).isEqualTo(1400.0);
        assertThat(q.changePct()).isEqualTo(1.07);
        assertThat(q.time()).isEqualTo("2026-08-18 14:35:00");
    }

    @DisplayName("东财K线中间结构解析出K线柱并完成手→股归一化")
    @Test
    void givenKlineRaws_whenParseKline_thenReturnBars() {
        List<RawKlineBar> raws = List.of(
                new RawKlineBar("2026-08-14", 1405.00, 1410.50, 1412.00, 1400.00, 22110, 3123456789.00, 0.85),
                new RawKlineBar("2026-08-15", 1410.50, 1418.00, 1420.00, 1408.00, 19876, 2812345678.00, 0.85),
                new RawKlineBar("2026-08-18", 1418.00, 1415.00, 1428.00, 1405.50, 23456, 3312345678.00, 1.59));
        List<KlineBar> bars = MarketDataParser.parseKline(raws);
        assertThat(bars).hasSize(3);
        assertThat(bars.get(0).date()).isEqualTo("2026-08-14");
        assertThat(bars.get(2).close()).isEqualTo(1415.0);
        assertThat(bars.get(2).volume()).isEqualTo(2345600L);
    }

    @DisplayName("股票搜索中间结构解析出命中并归一化市场名（非A股过滤已下沉infrastructure）")
    @Test
    void givenStockHitRaws_whenParseSearch_thenReturnHitsWithMarketName() {
        List<RawStockHit> raws = List.of(
                new RawStockHit("600519", "贵州茅台", "1", "沪A"),
                new RawStockHit("000858", "五粮液", "0", "深A"));
        List<StockHit> hits = MarketDataParser.parseSearch(raws);
        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).code()).isEqualTo("600519");
        assertThat(hits.get(0).marketName()).isEqualTo("沪市");
        assertThat(hits.get(1).marketName()).isEqualTo("深市");
    }

    @DisplayName("北交所代码映射到北交所市场")
    @Test
    void givenBeijingStockCode_whenParseSearch_thenMapToBeijingMarket() {
        List<StockHit> hits = MarketDataParser.parseSearch(
                List.of(new RawStockHit("830799", "艾融软件", "0", "北A")));
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).marketName()).isEqualTo("北交所");
    }

    @DisplayName("财务中间结构构建出财务指标并截断报告期")
    @Test
    void givenFinancialRaws_whenBuildFinancials_thenReturnFinancials() {
        List<RawFinancialIndicator> raws = List.of(
                new RawFinancialIndicator("2025-12-31 00:00:00", 68.26, 210.32,
                        174100000000.0, 86200000000.0, 34.12, 91.90),
                new RawFinancialIndicator("2024-12-31 00:00:00", 62.00, 195.10,
                        164900000000.0, 81500000000.0, 33.50, 91.60));
        Financials f = MarketDataParser.buildFinancials("600519", "贵州茅台", 21.35, 7.82, raws);
        assertThat(f.pe()).isEqualTo(21.35);
        assertThat(f.indicators()).hasSize(2);
        assertThat(f.indicators().get(0).reportDate()).isEqualTo("2025-12-31");
        assertThat(f.indicators().get(0).weightedRoe()).isEqualTo(34.12);
    }

    @DisplayName("新闻中间结构解析剥离HTML并返回条目")
    @Test
    void givenNewsRaws_whenParseNews_thenStripHtmlAndReturnItems() {
        List<RawNewsItem> raws = List.of(
                new RawNewsItem("贵州茅台发布2025年度报告",
                        "贵州茅台<em>发布</em>2025年年度报告，营收与净利润保持增长&nbsp;……",
                        "证券时报", "2026-04-01 20:00:00", "https://finance.eastmoney.com/a/1.html"),
                new RawNewsItem("北向资金增持贵州茅台", "数据显示北向资金近期持续增持……",
                        "东方财富网", "2026-04-02 09:30:00", "https://finance.eastmoney.com/a/2.html"));
        List<NewsItem> items = MarketDataParser.parseNews(raws);
        assertThat(items).hasSize(2);
        assertThat(items.get(0).title()).isEqualTo("贵州茅台发布2025年度报告");
        assertThat(items.get(0).summary()).doesNotContain("<em>").doesNotContain("&nbsp;");
    }

    @DisplayName("指数中间结构构建出指数速览")
    @Test
    void givenIndexRaws_whenBuildOverview_thenReturnIndices() {
        List<RawIndexQuote> raws = List.of(
                new RawIndexQuote("000001", "上证指数", 3250.10, 27.40, 0.85),
                new RawIndexQuote("399001", "深证成指", 10680.50, 126.60, 1.20),
                new RawIndexQuote("399006", "创业板指", 2150.30, -7.55, -0.35));
        MarketOverview o = MarketDataParser.buildOverview(raws);
        assertThat(o.indices()).hasSize(3);
        assertThat(o.indices().get(0).code()).isEqualTo("000001");
        assertThat(o.indices().get(0).changePct()).isEqualTo(0.85);
    }

    @DisplayName("腾讯K线JSON解析出K线柱")
    @Test
    void givenTencentKlineJson_whenParseTencentKline_thenReturnBars() throws IOException {
        List<KlineBar> bars = MarketDataParser.parseTencentKline(
                fixture("tencent-kline.json"), "sh600519", "day");
        assertThat(bars).hasSize(3);
        assertThat(bars.get(0).date()).isEqualTo("2026-08-14");
        assertThat(bars.get(2).close()).isEqualTo(1297.99);
        assertThat(bars.get(2).volume()).isEqualTo(3872300L); // 手 → 股
        assertThat(bars.get(2).amount()).isEqualTo(0.0); // 腾讯无成交额字段
        assertThat(bars.get(2).amplitudePct()).isEqualTo(0.0); // 腾讯无振幅字段
    }

    @DisplayName("腾讯行情文本解析出报价")
    @Test
    void givenTencentRawText_whenParseTencentQuote_thenReturnQuote() {
        Quote q = MarketDataParser.parseTencentQuote(tencentRaw, "600519");
        assertThat(q.code()).isEqualTo("600519");
        assertThat(q.name()).isEqualTo("贵州茅台");
        assertThat(q.price()).isEqualTo(1237.00);
        assertThat(q.prevClose()).isEqualTo(1251.24);
        assertThat(q.open()).isEqualTo(1250.01);
        assertThat(q.change()).isEqualTo(-14.24);
        assertThat(q.changePct()).isEqualTo(-1.14);
        assertThat(q.high()).isEqualTo(1256.13);
        assertThat(q.low()).isEqualTo(1231.05);
        assertThat(q.volume()).isEqualTo(3_123_900L);      // 31239手 ×100 → 股
        assertThat(q.amount()).isEqualTo(3_867_310_000.0); // 386731万 ×10000 → 元
        assertThat(q.pe()).isEqualTo(18.99);
        assertThat(q.pb()).isEqualTo(6.15);
        assertThat(q.time()).isEqualTo("2026-09-24 16:14");
    }

    @DisplayName("腾讯指数文本解析出三指数速览")
    @Test
    void givenTencentIndicesRaw_whenBuildTencentOverview_thenReturnThreeIndices() {
        MarketOverview o = MarketDataParser.buildTencentOverview(tencentIndicesRaw);
        assertThat(o.indices()).hasSize(3);
        assertThat(o.indices().get(0).code()).isEqualTo("000001");
        assertThat(o.indices().get(0).name()).isEqualTo("上证指数");
        assertThat(o.indices().get(0).price()).isEqualTo(3888.37);
        assertThat(o.indices().get(0).change()).isEqualTo(-48.15);
        assertThat(o.indices().get(0).changePct()).isEqualTo(-1.22);
        assertThat(o.time()).isNotBlank();
    }

    @DisplayName("新浪指数文本构建出指数速览")
    @Test
    void givenSinaIndicesRaw_whenBuildSinaOverview_thenReturnIndices() throws IOException {
        MarketOverview o = MarketDataParser.buildSinaOverview(fixtureText("sina-indices.txt"));
        assertThat(o.indices()).hasSize(3);
        assertThat(o.indices().get(0).name()).isEqualTo("上证指数");
        assertThat(o.indices().get(0).price()).isEqualTo(3990.30);
        assertThat(o.indices().get(1).changePct()).isEqualTo(-0.56);
    }

    @DisplayName("手数成交量归一化为股数")
    @Test
    void givenVolumeInLots_whenNormalizeVolume_thenConvertToShares() {
        assertThat(MarketDataParser.normalizeVolume(38723, 5.007e9, 1297.99)).isEqualTo(3872300L);
        assertThat(MarketDataParser.normalizeVolume(3872283, 5.007e9, 1297.99)).isEqualTo(3872283L);
    }

    @DisplayName("空行情帧解析抛异常（data缺失，infrastructure侧口径）")
    @Test
    void givenEmptyQuoteJson_whenParseQuote_thenThrowBadResponse() {
        assertThatThrownBy(() -> EastmoneyFormat.parseQuote(MAPPER.createObjectNode()))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("行情数据为空");
    }

    private static JsonNode fixture(String name) throws IOException {
        return MAPPER.readTree(fixtureText(name));
    }

    private static String fixtureText(String name) throws IOException {
        try (InputStream in = MarketDataParserTest.class.getResourceAsStream("/fixtures/" + name)) {
            assert in != null : "fixture not found: " + name;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
