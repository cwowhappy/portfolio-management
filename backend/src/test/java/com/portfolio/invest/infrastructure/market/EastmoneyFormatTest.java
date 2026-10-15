package com.portfolio.invest.infrastructure.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.domain.market.KlineBar;
import com.portfolio.invest.domain.market.MarketDataException;
import com.portfolio.invest.domain.market.MarketOverview;
import com.portfolio.invest.domain.market.NewsItem;
import com.portfolio.invest.domain.market.Quote;
import com.portfolio.invest.domain.market.RawFinancialIndicator;
import com.portfolio.invest.domain.market.RawIndexQuote;
import com.portfolio.invest.domain.market.RawKlineBar;
import com.portfolio.invest.domain.market.RawNewsItem;
import com.portfolio.invest.domain.market.RawStockHit;
import com.portfolio.invest.domain.market.StockHit;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 东财 wire format 提取层测试（2026-10-15 L2 下沉收口后承接原 domain 侧 JsonNode fixture）：
 * 字段码/路径导航/非 A 股过滤/行形态跳过的断言全在本类；domain {@code MarketDataParserTest}
 * 改 Raw→DTO 构造后，本类经组合方法（parse 系列 / buildOverview）钉住端到端语义与下沉前一致。
 */
class EastmoneyFormatTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String s) {
        try {
            return mapper.readTree(s);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static JsonNode fixture(String name) throws IOException {
        try (InputStream in = EastmoneyFormatTest.class.getResourceAsStream("/fixtures/" + name)) {
            assert in != null : "fixture not found: " + name;
            return new ObjectMapper().readTree(in);
        }
    }

    private static String fixtureText(String name) throws IOException {
        try (InputStream in = EastmoneyFormatTest.class.getResourceAsStream("/fixtures/" + name)) {
            assert in != null : "fixture not found: " + name;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ———— extractQuote（第三轮已下沉，本类补组合层回归） ————

    @DisplayName("行情帧data缺失抛BAD_RESPONSE（文案与触发口径不变）")
    @Test
    void givenQuoteDataMissing_whenParseQuote_thenThrowBadResponse() {
        assertThatThrownBy(() -> EastmoneyFormat.parseQuote(json("{}")))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("行情数据为空");
        assertThatThrownBy(() -> EastmoneyFormat.parseQuote(json("{\"data\":null}")))
                .isInstanceOf(MarketDataException.class);
    }

    @DisplayName("行情帧无时间戳且异常值字符串归空")
    @Test
    void givenQuoteDashValues_whenParseQuote_thenNullablesEmpty() {
        Quote q = EastmoneyFormat.parseQuote(json(
                "{\"data\":{\"f43\":10,\"f57\":\"600519\",\"f58\":\"贵州茅台\",\"f162\":\"-\",\"f167\":\"-\"}}"));
        assertThat(q.time()).isEmpty();
        assertThat(q.pe()).isNull();
        assertThat(q.pb()).isNull();
    }

    // ———— 指数速览（push2 diff） ————

    @DisplayName("指数帧提取f12/f14/f2/f4/f3为中间结构")
    @Test
    void givenOverviewJson_whenExtractOverview_thenMapFieldCodes() throws IOException {
        List<RawIndexQuote> raws = EastmoneyFormat.extractOverview(fixture("eastmoney-overview.json"));
        assertThat(raws).hasSize(3);
        assertThat(raws.get(0).code()).isEqualTo("000001");
        assertThat(raws.get(0).name()).isEqualTo("上证指数");
        assertThat(raws.get(0).price()).isEqualTo(3250.10);
        assertThat(raws.get(0).change()).isEqualTo(27.40);
        assertThat(raws.get(0).changePct()).isEqualTo(0.85);
    }

    @DisplayName("指数帧diff非数组提取为空")
    @Test
    void givenOverviewDiffNonArray_whenExtractOverview_thenEmpty() {
        assertThat(EastmoneyFormat.extractOverview(json("{\"data\":{}}"))).isEmpty();
    }

    @DisplayName("指数帧组合解析出速览")
    @Test
    void givenOverviewJson_whenBuildOverview_thenReturnIndices() throws IOException {
        MarketOverview o = EastmoneyFormat.buildOverview(fixture("eastmoney-overview.json"));
        assertThat(o.indices()).hasSize(3);
        assertThat(o.indices().get(0).code()).isEqualTo("000001");
        assertThat(o.indices().get(0).changePct()).isEqualTo(0.85);
    }

    @DisplayName("指数帧空数据组合解析抛BAD_RESPONSE")
    @Test
    void givenOverviewEmpty_whenBuildOverview_thenThrowBadResponse() {
        assertThatThrownBy(() -> EastmoneyFormat.buildOverview(json("{\"data\":{}}")))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("指数数据为空");
    }

    // ———— 股票搜索（QuotationCodeTable + 非 A 股过滤） ————

    @DisplayName("搜索帧提取命中并过滤指数等非A股条目")
    @Test
    void givenSearchJson_whenExtractSearch_thenFilterNonAShares() throws IOException {
        List<RawStockHit> hits = EastmoneyFormat.extractSearch(fixture("eastmoney-search.json"));
        assertThat(hits).hasSize(2);
        assertThat(hits).extracting(RawStockHit::code).containsExactly("600519", "000858");
        assertThat(hits).noneMatch(h -> "上证指数".equals(h.name()));
    }

    @DisplayName("搜索帧过滤各类非A股条目（SecurityTypeName判定为wire知识随字段码下沉）")
    @Test
    void givenNonAShareEntries_whenExtractSearch_thenFilterThemOut() {
        String template = "{\"QuotationCodeTable\":{\"Data\":["
                + "{\"Code\":\"000001\",\"Name\":\"平安银行\",\"MktNum\":\"0\"},"
                + "{\"Code\":\"600519\",\"Name\":\"贵州茅台\",\"MktNum\":\"1\"},"
                + "{\"Code\":\"900001\",\"Name\":\"某板块\",\"SecurityTypeName\":\"板块\",\"MktNum\":\"1\"},"
                + "{\"Code\":\"900002\",\"Name\":\"某基金\",\"SecurityTypeName\":\"基金\",\"MktNum\":\"1\"},"
                + "{\"Code\":\"900003\",\"Name\":\"某债\",\"SecurityTypeName\":\"转债\",\"MktNum\":\"1\"},"
                + "{\"Code\":\"900004\",\"Name\":\"某期货\",\"SecurityTypeName\":\"期货\",\"MktNum\":\"1\"},"
                + "{\"Code\":\"900005\",\"Name\":\"某指数\",\"SecurityTypeName\":\"指数\",\"MktNum\":\"1\"}"
                + "]}}";
        List<RawStockHit> hits = EastmoneyFormat.extractSearch(json(template));
        assertThat(hits).hasSize(2);
        assertThat(hits).extracting(RawStockHit::code).containsExactly("000001", "600519");
    }

    @DisplayName("搜索帧Data非数组提取为空")
    @Test
    void givenSearchDataNonArray_whenExtractSearch_thenEmpty() {
        assertThat(EastmoneyFormat.extractSearch(json("{\"QuotationCodeTable\":{}}"))).isEmpty();
    }

    @DisplayName("搜索帧组合解析出命中并归一化市场名")
    @Test
    void givenSearchJson_whenParseSearch_thenReturnHits() throws IOException {
        List<StockHit> hits = EastmoneyFormat.parseSearch(fixture("eastmoney-search.json"));
        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).code()).isEqualTo("600519");
        assertThat(hits.get(0).marketName()).isEqualTo("沪市");
        assertThat(hits.get(1).marketName()).isEqualTo("深市");
    }

    // ———— K线（data.klines CSV 行） ————

    @DisplayName("K线帧提取CSV行为中间结构（成交量保持原始手单位）")
    @Test
    void givenKlineJson_whenExtractKline_thenSplitCsvRows() throws IOException {
        List<RawKlineBar> bars = EastmoneyFormat.extractKline(fixture("eastmoney-kline.json"));
        assertThat(bars).hasSize(3);
        assertThat(bars.get(0).date()).isEqualTo("2026-08-14");
        assertThat(bars.get(2).close()).isEqualTo(1415.0);
        assertThat(bars.get(2).volume()).isEqualTo(23456L); // 原始单位（手），转股归一化在 domain
    }

    @DisplayName("K线帧klines为null抛BAD_RESPONSE")
    @Test
    void givenKlineKlinesNull_whenExtractKline_thenThrowBadResponse() {
        assertThatThrownBy(() -> EastmoneyFormat.extractKline(json("{\"data\":{\"klines\":null}}")))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("K线数据为空");
    }

    @DisplayName("K线帧klines缺失抛BAD_RESPONSE")
    @Test
    void givenKlineKlinesMissing_whenExtractKline_thenThrowBadResponse() {
        assertThatThrownBy(() -> EastmoneyFormat.extractKline(json("{}")))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("K线数据为空");
    }

    @DisplayName("K线帧行字段不足被跳过且整体为空时组合解析抛异常")
    @Test
    void givenKlineRowsAllShort_whenParseKline_thenThrowBadResponse() {
        assertThatThrownBy(() -> EastmoneyFormat.parseKline(json(
                "{\"data\":{\"klines\":[\"2026-08-18,1,2,3\"]}}")))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("K线数据为空");
        // extract 层单独断言：跳过短行后为空列表（空校验在 domain parseKline，口径不变）
        assertThat(EastmoneyFormat.extractKline(
                json("{\"data\":{\"klines\":[\"2026-08-18,1,2,3\"]}}"))).isEmpty();
    }

    @DisplayName("K线帧组合解析完成手→股归一化")
    @Test
    void givenKlineJson_whenParseKline_thenNormalizeVolumeToShares() throws IOException {
        List<KlineBar> bars = EastmoneyFormat.parseKline(fixture("eastmoney-kline.json"));
        assertThat(bars).hasSize(3);
        assertThat(bars.get(2).volume()).isEqualTo(2345600L);
    }

    // ———— 财务指标（F10 数据中台） ————

    @DisplayName("财务帧提取F10字段码为中间结构（报告期原文透传）")
    @Test
    void givenFinancialsJson_whenExtractFinancials_thenMapFieldCodes() throws IOException {
        List<RawFinancialIndicator> raws = EastmoneyFormat.extractFinancials(fixture("eastmoney-financials.json"));
        assertThat(raws).hasSize(2);
        assertThat(raws.get(0).reportDate()).isEqualTo("2025-12-31 00:00:00"); // 截断归一化在 domain
        assertThat(raws.get(0).eps()).isEqualTo(68.26);
        assertThat(raws.get(0).roe()).isEqualTo(34.12);
    }

    @DisplayName("财务帧data为null或非数组抛BAD_RESPONSE")
    @Test
    void givenFinancialDataNullOrNonArray_whenExtractFinancials_thenThrowBadResponse() {
        assertThatThrownBy(() -> EastmoneyFormat.extractFinancials(json("{\"result\":{\"data\":null}}")))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("财务数据为空");
        assertThatThrownBy(() -> EastmoneyFormat.extractFinancials(json("{\"result\":{\"data\":{}}}")))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("财务数据为空");
    }

    @DisplayName("财务帧data缺失抛BAD_RESPONSE")
    @Test
    void givenFinancialDataMissing_whenExtractFinancials_thenThrowBadResponse() {
        assertThatThrownBy(() -> EastmoneyFormat.extractFinancials(json("{}")))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("财务数据为空");
    }

    @DisplayName("财务帧可空字段缺失归null且组合解析截断报告期")
    @Test
    void givenSparseFinancialJson_whenParseFinancials_thenNullablesAndDateTruncation() {
        var list = EastmoneyFormat.parseFinancials(json(
                "{\"result\":{\"data\":[{\"REPORT_DATE\":\"2026-06-30 00:00:00\",\"EPSJB\":2.0}]}}"));
        assertThat(list).hasSize(1);
        assertThat(list.get(0).reportDate()).isEqualTo("2026-06-30");
        assertThat(list.get(0).bps()).isNull();
        assertThat(list.get(0).netProfit()).isNull();
    }

    // ———— 新闻（cmsArticleWebOld） ————

    @DisplayName("新闻帧提取原文（HTML不剥离，归一化在domain）")
    @Test
    void givenNewsJson_whenExtractNews_thenRawTextPassthrough() throws IOException {
        List<RawNewsItem> items = EastmoneyFormat.extractNews(fixture("eastmoney-news.json"));
        assertThat(items).hasSize(2);
        assertThat(items.get(0).title()).isEqualTo("贵州茅台发布2025年度报告");
        assertThat(items.get(0).content()).contains("<em>").contains("&nbsp;");
        assertThat(items.get(0).media()).isEqualTo("证券时报");
    }

    @DisplayName("新闻帧节点非数组提取为空")
    @Test
    void givenNewsNodeNonArray_whenExtractNews_thenEmpty() {
        assertThat(EastmoneyFormat.extractNews(json("{\"result\":{}}"))).isEmpty();
    }

    @DisplayName("新闻帧组合解析完成HTML剥离与摘要截断")
    @Test
    void givenNewsJson_whenParseNews_thenStripHtmlAndAbbreviate() throws IOException {
        List<NewsItem> items = EastmoneyFormat.parseNews(fixture("eastmoney-news.json"));
        assertThat(items).hasSize(2);
        assertThat(items.get(0).summary()).doesNotContain("<em>").doesNotContain("&nbsp;");
    }

    // ———— quote fixture 引用自检（防 fixture 平移后误删共享资源） ————

    @DisplayName("quote fixture仍可用于extractQuote")
    @Test
    void givenQuoteFixture_whenExtractQuote_thenCodeMapped() throws IOException {
        var raw = EastmoneyFormat.extractQuote(new ObjectMapper().readTree(fixtureText("eastmoney-quote.json")));
        assertThat(raw.code()).isEqualTo("600519");
    }
}
