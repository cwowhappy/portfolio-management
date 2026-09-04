package com.portfolio.invest.domain.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

class MarketDataParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static JsonNode quoteJson;
    private static JsonNode klineJson;
    private static JsonNode searchJson;
    private static JsonNode financialsJson;
    private static JsonNode newsJson;
    private static JsonNode overviewJson;
    private static String sinaRaw;

    @BeforeAll
    static void loadFixtures() throws IOException {
        quoteJson = fixture("eastmoney-quote.json");
        klineJson = fixture("eastmoney-kline.json");
        searchJson = fixture("eastmoney-search.json");
        financialsJson = fixture("eastmoney-financials.json");
        newsJson = fixture("eastmoney-news.json");
        overviewJson = fixture("eastmoney-overview.json");
        sinaRaw = fixtureText("sina-quote.txt");
    }

    @DisplayName("东财行情JSON解析出报价")
    @Test
    void givenEastmoneyQuoteJson_whenParseQuote_thenReturnQuote() {
        Quote q = MarketDataParser.parseQuote(quoteJson);
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

    @DisplayName("东财K线JSON解析出K线柱")
    @Test
    void givenEastmoneyKlineJson_whenParseKline_thenReturnBars() {
        List<KlineBar> bars = MarketDataParser.parseKline(klineJson);
        assertThat(bars).hasSize(3);
        assertThat(bars.get(0).date()).isEqualTo("2026-08-14");
        assertThat(bars.get(2).close()).isEqualTo(1415.0);
        assertThat(bars.get(2).volume()).isEqualTo(2345600L);
    }

    @DisplayName("东财搜索JSON解析出命中并过滤指数")
    @Test
    void givenEastmoneySearchJson_whenParseSearch_thenReturnHitsAndFilterIndices() {
        List<StockHit> hits = MarketDataParser.parseSearch(searchJson);
        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).code()).isEqualTo("600519");
        assertThat(hits.get(0).marketName()).isEqualTo("沪市");
        assertThat(hits).noneMatch(h -> "上证指数".equals(h.name()));
    }

    @DisplayName("北交所代码映射到北交所市场")
    @Test
    void givenBeijingStockCode_whenParseSearch_thenMapToBeijingMarket() throws IOException {
        JsonNode node = MAPPER.readTree(
                "{\"QuotationCodeTable\":{\"Data\":["
                        + "{\"Code\":\"830799\",\"Name\":\"艾融软件\",\"MktNum\":\"0\",\"SecurityTypeName\":\"北A\"}]}}");
        List<StockHit> hits = MarketDataParser.parseSearch(node);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).marketName()).isEqualTo("北交所");
    }

    @DisplayName("财务JSON构建出财务指标")
    @Test
    void givenFinancialJson_whenBuildFinancials_thenReturnFinancials() {
        Financials f = MarketDataParser.buildFinancials("600519", "贵州茅台", 21.35, 7.82, financialsJson);
        assertThat(f.pe()).isEqualTo(21.35);
        assertThat(f.indicators()).hasSize(2);
        assertThat(f.indicators().get(0).reportDate()).isEqualTo("2025-12-31");
        assertThat(f.indicators().get(0).weightedRoe()).isEqualTo(34.12);
    }

    @DisplayName("新闻JSON解析剥离HTML并返回条目")
    @Test
    void givenNewsJson_whenParseNews_thenStripHtmlAndReturnItems() {
        List<NewsItem> items = MarketDataParser.parseNews(newsJson);
        assertThat(items).hasSize(2);
        assertThat(items.get(0).title()).isEqualTo("贵州茅台发布2025年度报告");
        assertThat(items.get(0).summary()).doesNotContain("<em>").doesNotContain("&nbsp;");
    }

    @DisplayName("大盘JSON构建出指数速览")
    @Test
    void givenOverviewJson_whenBuildOverview_thenReturnIndices() {
        MarketOverview o = MarketDataParser.buildOverview(overviewJson);
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

    @DisplayName("空行情JSON解析抛异常")
    @Test
    void givenEmptyQuoteJson_whenParseQuote_thenThrowBadResponse() {
        assertThatThrownBy(() -> MarketDataParser.parseQuote(MAPPER.createObjectNode()))
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
