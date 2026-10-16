package com.portfolio.invest.domain.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.infrastructure.market.EastmoneyFormat;
import java.io.IOException;
import java.io.InputStream;
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
 * {@code infrastructure/market/EastmoneyFormatTest}，本类以 {@code Raw*} 中间结构构造入参
 * （2026-10-15 L2 下沉），断言语义与下沉前一致；新浪/腾讯文本兜底解析用例已平移至
 * {@code infrastructure/market/SinaFormatTest}/{@code TencentFormatTest}（2026-10-16 L2-二期）。
 */
class MarketDataParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static JsonNode quoteJson;

    @BeforeAll
    static void loadFixtures() throws IOException {
        quoteJson = fixture("eastmoney-quote.json");
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
        try (InputStream in = MarketDataParserTest.class.getResourceAsStream("/fixtures/" + name)) {
            assert in != null : "fixture not found: " + name;
            return MAPPER.readTree(in);
        }
    }
}
