package com.portfolio.invest.domain.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.domain.market.MarketDataException;
import com.portfolio.invest.domain.market.MarketDataParser;
import com.portfolio.invest.domain.market.Quote;
import com.portfolio.invest.domain.market.StockHit;
import com.portfolio.invest.domain.market.StockRef;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** MarketDataParser / StockRef 的边界与异常分支。 */
class MarketDataParserEdgeCasesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String s) {
        try {
            return mapper.readTree(s);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ———— parseQuote ————

    @DisplayName("parseQuote数据缺失抛BAD_RESPONSE")
    @Test
    void givenQuoteDataMissing_whenParseQuote_thenThrowBadResponse() {
        assertThatThrownBy(() -> MarketDataParser.parseQuote(json("{}")))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("行情数据为空");
        assertThatThrownBy(() -> MarketDataParser.parseQuote(json("{\"data\":null}")))
                .isInstanceOf(MarketDataException.class);
    }

    @DisplayName("parseQuote价格无效抛BAD_RESPONSE")
    @Test
    void givenQuotePriceInvalid_whenParseQuote_thenThrowBadResponse() {
        assertThatThrownBy(() -> MarketDataParser.parseQuote(json("{\"data\":{\"f43\":0}}")))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("行情价格为空或无效");
    }

    @DisplayName("parseQuote无时间戳时time为空")
    @Test
    void givenQuoteWithoutTimestamp_whenParseQuote_thenTimeEmpty() {
        Quote q = MarketDataParser.parseQuote(json(
                "{\"data\":{\"f43\":10,\"f57\":\"600519\",\"f58\":\"贵州茅台\",\"f86\":0}}"));
        assertThat(q.time()).isEmpty();
        assertThat(q.volume()).isZero();
        assertThat(q.pe()).isNull();
    }

    @DisplayName("parseQuote异常值字符串视为空")
    @Test
    void givenQuoteDashValueStrings_whenParseQuote_thenTreatedAsEmpty() {
        Quote q = MarketDataParser.parseQuote(json(
                "{\"data\":{\"f43\":10,\"f57\":\"600519\",\"f58\":\"贵州茅台\",\"f162\":\"-\",\"f167\":\"-\"}}"));
        assertThat(q.pe()).isNull();
        assertThat(q.pb()).isNull();
    }

    // ———— normalizeVolume ————

    @DisplayName("normalizeVolume零值直接返回")
    @Test
    void givenZeroFactor_whenNormalizeVolume_thenReturnDirectly() {
        assertThat(MarketDataParser.normalizeVolume(0, 100, 10)).isZero();
        assertThat(MarketDataParser.normalizeVolume(100, 0, 10)).isEqualTo(100);
        assertThat(MarketDataParser.normalizeVolume(100, 100, 0)).isEqualTo(100);
    }

    @DisplayName("normalizeVolume按手转股")
    @Test
    void givenVolumeInLots_whenNormalizeVolume_thenConvertToShares() {
        // raw=100 手, amount/price=10000 股 → 转股
        assertThat(MarketDataParser.normalizeVolume(100, 100_000, 10)).isEqualTo(10_000);
    }

    // ———— parseSinaQuote ————

    @DisplayName("parseSinaQuote只有一个引号视为格式异常")
    @Test
    void givenSinaQuoteWithOnlyOpeningQuote_whenParseSinaQuote_thenFormatError() {
        // 有起始引号但无收尾引号：end <= start
        assertThatThrownBy(() -> MarketDataParser.parseSinaQuote(
                "var hq=\"贵州茅台,1,2,3", "600519"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("新浪行情响应格式异常");
    }

    @DisplayName("parseSinaQuote昨收为零时涨跌幅归零避免除零")
    @Test
    void givenPrevCloseZero_whenParseSinaQuote_thenChangePctZeroAvoidingDivByZero() {
        Quote q = MarketDataParser.parseSinaQuote(
                "var hq=\"贵州茅台,10,0,10.5,11,10.8,10.2,10.5,12345,100000\";", "600519");
        assertThat(q.change()).isEqualTo(10.5);
        assertThat(q.changePct()).isEqualTo(0);
    }

    @DisplayName("parseSinaQuote格式异常抛BAD_RESPONSE")
    @Test
    void givenMalformedSinaQuote_whenParseSinaQuote_thenThrowBadResponse() {
        assertThatThrownBy(() -> MarketDataParser.parseSinaQuote("no quotes", "600519"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("新浪行情响应格式异常");
        assertThatThrownBy(() -> MarketDataParser.parseSinaQuote(
                "var hq=\"贵州茅台,1,2\";", "600519"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("新浪行情字段不足");
    }

    @DisplayName("parseSinaQuote价格无效抛BAD_RESPONSE")
    @Test
    void givenSinaQuotePriceInvalid_whenParseSinaQuote_thenThrowBadResponse() {
        assertThatThrownBy(() -> MarketDataParser.parseSinaQuote(
                "var hq=\"贵州茅台,1,2,0,0,0,0,0,0,0\";", "600519"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("新浪行情价格无效");
    }

    @DisplayName("parseSinaQuote十字段无时间")
    @Test
    void givenSinaQuoteWithTenFields_whenParseSinaQuote_thenTimeEmpty() {
        // 10 个字段：有成交量、无时间（f.length<=31）
        Quote q = MarketDataParser.parseSinaQuote(
                "var hq=\"贵州茅台,10,9,10.5,11,10.8,10.2,10.5,12345,100000\";", "600519");
        assertThat(q.price()).isEqualTo(10.5);
        assertThat(q.volume()).isEqualTo(12345);
        assertThat(q.time()).isEmpty();
    }

    // ———— parseKline ————

    @DisplayName("parseKline数据为null抛BAD_RESPONSE")
    @Test
    void givenKlineDataNull_whenParseKline_thenThrowBadResponse() {
        assertThatThrownBy(() -> MarketDataParser.parseKline(json("{\"data\":{\"klines\":null}}")))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("K线数据为空");
    }

    @DisplayName("parseKline数据缺失抛BAD_RESPONSE")
    @Test
    void givenKlineDataMissing_whenParseKline_thenThrowBadResponse() {
        assertThatThrownBy(() -> MarketDataParser.parseKline(json("{}")))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("K线数据为空");
    }

    @DisplayName("parseKline行字段不足被跳过并整体为空时抛异常")
    @Test
    void givenKlineRowsAllShort_whenParseKline_thenThrowBadResponse() {
        assertThatThrownBy(() -> MarketDataParser.parseKline(json(
                "{\"data\":{\"klines\":[\"2026-08-18,1,2,3\"]}}")))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("K线数据为空");
    }

    // ———— parseTencentKline ————

    @DisplayName("parseTencentKlineqfq键非数组时回退裸周期键")
    @Test
    void givenQfqKeyNonArray_whenParseTencentKline_thenFallbackToBarePeriodKey() {
        List<com.portfolio.invest.domain.market.KlineBar> bars = MarketDataParser.parseTencentKline(
                json("{\"data\":{\"sh600519\":{\"qfqday\":{\"unexpected\":true},\"day\":"
                        + "[[\"2026-08-18\",\"1\",\"2\",\"3\",\"1\",\"100\"]]}}}"),
                "sh600519", "day");
        assertThat(bars).hasSize(1);
        assertThat(bars.get(0).volume()).isEqualTo(10_000);
    }

    @DisplayName("parseTencentKline非数组行跳过且全部无效时抛BAD_RESPONSE")
    @Test
    void givenAllTencentRowsNonArray_whenParseTencentKline_thenThrowBadResponse() {
        assertThatThrownBy(() -> MarketDataParser.parseTencentKline(
                json("{\"data\":{\"sh600519\":{\"qfqday\":[\"not-an-array\"]}}}"), "sh600519", "day"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("腾讯K线数据为空");
    }

    @DisplayName("parseTencentKline缺qfq前缀时回退裸周期键")
    @Test
    void givenQfqPrefixMissing_whenParseTencentKline_thenFallbackToBarePeriodKey() {
        List<com.portfolio.invest.domain.market.KlineBar> bars = MarketDataParser.parseTencentKline(
                json("{\"data\":{\"sh600519\":{\"day\":[[\"2026-08-18\",\"1\",\"2\",\"3\",\"1\",\"100\"]]}}}"),
                "sh600519", "day");
        assertThat(bars).hasSize(1);
        assertThat(bars.get(0).volume()).isEqualTo(10_000); // 手 → 股
    }

    @DisplayName("parseTencentKline数据为空抛BAD_RESPONSE")
    @Test
    void givenTencentDataEmpty_whenParseTencentKline_thenThrowBadResponse() {
        assertThatThrownBy(() -> MarketDataParser.parseTencentKline(
                json("{\"data\":{\"sh600519\":{}}}"), "sh600519", "day"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("腾讯K线数据为空");
    }

    @DisplayName("parseTencentKline短行跳过并按日期升序排序")
    @Test
    void givenShortAndValidRows_whenParseTencentKline_thenSkipShortsAndSortAscending() {
        List<com.portfolio.invest.domain.market.KlineBar> bars = MarketDataParser.parseTencentKline(
                json("{\"data\":{\"sh600519\":{\"qfqday\":["
                        + "[\"2026-08-18\",\"1\",\"2\",\"3\",\"1\",\"100\"],"
                        + "[\"short\"],"
                        + "[\"2026-08-14\",\"1\",\"2\",\"3\",\"1\",\"200\",\"7\",\"8\"]"
                        + "]}}}"),
                "sh600519", "day");
        assertThat(bars).hasSize(2);
        assertThat(bars.get(0).date()).isEqualTo("2026-08-14");
        assertThat(bars.get(0).amount()).isEqualTo(7.0);
        assertThat(bars.get(0).amplitudePct()).isEqualTo(8.0);
    }

    // ———— parseSearch / marketNameOf ————

    @DisplayName("parseSearch北交所与9开头代码的市场识别")
    @Test
    void givenBjAndNinePrefixCodes_whenParseSearch_thenRecognizeMarkets() {
        List<StockHit> hits = MarketDataParser.parseSearch(json(
                "{\"QuotationCodeTable\":{\"Data\":["
                        + "{\"Code\":\"430047\",\"Name\":\"诺思兰德\",\"MktNum\":\"0\"},"
                        + "{\"Code\":\"830047\",\"Name\":\"某北证\",\"MktNum\":\"0\"},"
                        + "{\"Code\":\"900001\",\"Name\":\"某沪B\",\"MktNum\":\"1\"}"
                        + "]}}"));
        assertThat(hits).extracting(StockHit::marketName)
                .containsExactly("北交所", "北交所", "沪市");
    }

    @DisplayName("parseSearch数据非数组返回空")
    @Test
    void givenSearchDataNonArray_whenParseSearch_thenReturnEmpty() {
        assertThat(MarketDataParser.parseSearch(json("{\"QuotationCodeTable\":{}}"))).isEmpty();
    }

    @DisplayName("parseSearch过滤各类非A股条目")
    @Test
    void givenNonAShareEntries_whenParseSearch_thenFilterThemOut() {
        String template = "{\"QuotationCodeTable\":{\"Data\":["
                + "{\"Code\":\"000001\",\"Name\":\"平安银行\",\"MktNum\":\"0\"},"
                + "{\"Code\":\"600519\",\"Name\":\"贵州茅台\",\"MktNum\":\"1\"},"
                + "{\"Code\":\"900001\",\"Name\":\"某板块\",\"SecurityTypeName\":\"板块\",\"MktNum\":\"1\"},"
                + "{\"Code\":\"900002\",\"Name\":\"某基金\",\"SecurityTypeName\":\"基金\",\"MktNum\":\"1\"},"
                + "{\"Code\":\"900003\",\"Name\":\"某债\",\"SecurityTypeName\":\"转债\",\"MktNum\":\"1\"},"
                + "{\"Code\":\"900004\",\"Name\":\"某期货\",\"SecurityTypeName\":\"期货\",\"MktNum\":\"1\"},"
                + "{\"Code\":\"900005\",\"Name\":\"某指数\",\"SecurityTypeName\":\"指数\",\"MktNum\":\"1\"}"
                + "]}}";
        List<StockHit> hits = MarketDataParser.parseSearch(json(template));
        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).marketName()).isEqualTo("深市");
        assertThat(hits.get(1).marketName()).isEqualTo("沪市");
    }

    @DisplayName("parseSearch市场号1的非沪深代码按沪市")
    @Test
    void givenMktNum1CodeBeyondShSz_whenParseSearch_thenMapToShanghai() {
        List<StockHit> hits = MarketDataParser.parseSearch(json(
                "{\"QuotationCodeTable\":{\"Data\":[{\"Code\":\"700001\",\"Name\":\"X\",\"MktNum\":\"1\"}]}}"));
        assertThat(hits.get(0).marketName()).isEqualTo("沪市");
    }

    // ———— 财务/新闻/指数 ————

    @DisplayName("parseFinancialIndicators数据为null或非数组抛BAD_RESPONSE")
    @Test
    void givenFinancialDataNullOrNonArray_whenParseFinancialIndicators_thenThrowBadResponse() {
        assertThatThrownBy(() -> MarketDataParser.parseFinancialIndicators(json("{\"result\":{\"data\":null}}")))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("财务数据为空");
        assertThatThrownBy(() -> MarketDataParser.parseFinancialIndicators(json("{\"result\":{\"data\":{}}}")))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("财务数据为空");
    }

    @DisplayName("parseNews长摘要截断为120字加省略号")
    @Test
    void givenLongNewsSummary_whenParseNews_thenTruncateTo120CharsWithEllipsis() {
        String longContent = "长".repeat(200);
        var list = MarketDataParser.parseNews(json(
                "{\"result\":{\"cmsArticleWebOld\":[{\"title\":\"t\",\"content\":\"" + longContent
                        + "\",\"mediaName\":\"m\",\"date\":\"d\",\"url\":\"u\"}]}}"));
        assertThat(list.get(0).summary()).hasSize(121).endsWith("…");
    }

    @DisplayName("parseFinancialIndicators数据缺失抛BAD_RESPONSE")
    @Test
    void givenFinancialDataMissing_whenParseFinancialIndicators_thenThrowBadResponse() {
        assertThatThrownBy(() -> MarketDataParser.parseFinancialIndicators(json("{}")))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("财务数据为空");
    }

    @DisplayName("parseNews数据非数组返回空")
    @Test
    void givenNewsDataNonArray_whenParseNews_thenReturnEmpty() {
        assertThat(MarketDataParser.parseNews(json("{\"result\":{}}"))).isEmpty();
    }

    @DisplayName("parseOverview数据非数组返回空")
    @Test
    void givenOverviewDataNonArray_whenParseOverview_thenReturnEmpty() {
        assertThat(MarketDataParser.parseOverview(json("{\"data\":{}}"))).isEmpty();
    }

    @DisplayName("buildOverview空数据抛BAD_RESPONSE")
    @Test
    void givenOverviewDataEmpty_whenBuildOverview_thenThrowBadResponse() {
        assertThatThrownBy(() -> MarketDataParser.buildOverview(json("{\"data\":{}}")))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("指数数据为空");
    }

    @DisplayName("buildSinaOverview单边引号与字段不足行被跳过")
    @Test
    void givenUnbalancedQuoteAndShortRows_whenBuildSinaOverview_thenSkipRows() {
        var o = MarketDataParser.buildSinaOverview(
                "var hq_str_s_sh000001=\"上证指数,3990.30,7.65,0.19\";"
                        + "var hq_str_s_sz399006=\"创业板指,3200.00\";" // 字段不足 4 列 → 跳过
                        + "var hq_str_s_sz399001=\"深证成指,14622.50,-81.77,-0.56"); // 无收尾引号 → 跳过
        assertThat(o.indices()).hasSize(1);
        assertThat(o.indices().get(0).code()).isEqualTo("000001");
    }

    @DisplayName("buildSinaOverview变量名无等号或无下划线时代码退化提取")
    @Test
    void givenVarWithoutEqualsOrUnderscore_whenBuildSinaOverview_thenDegradeCodeExtraction() {
        var o = MarketDataParser.buildSinaOverview(
                "\"1,2,3,4\";"                    // 无 '=' → 代码为空串
                        + "var x_12=\"A,1,2,3\";"        // 下划线后 id 仅 2 位 → 原样返回
                        + "var hq=\"B,1,2,3\";");        // 无下划线 → 整个变量名退化提取
        assertThat(o.indices()).hasSize(3);
        assertThat(o.indices().get(0).code()).isEmpty();
        assertThat(o.indices().get(1).code()).isEqualTo("12");
        assertThat(o.indices().get(2).code()).isEqualTo("r hq");
    }

    @DisplayName("buildSinaOverview坏行跳过")
    @Test
    void givenBadRows_whenBuildSinaOverview_thenSkipThem() {
        var o = MarketDataParser.buildSinaOverview(
                "broken;"
                        + "var hq_str_s_sh000001=\"上证指数,3990.30,7.65,0.19,1,2\";"
                        + "var hq_str_s_sz399001=\"深证成指,14622.50,-81.77,-0.56,1,2\";");
        assertThat(o.indices()).hasSize(2);
        assertThat(o.indices().get(0).name()).isEqualTo("上证指数");
    }

    @DisplayName("buildSinaOverview全坏行抛BAD_RESPONSE")
    @Test
    void givenAllBadRows_whenBuildSinaOverview_thenThrowBadResponse() {
        assertThatThrownBy(() -> MarketDataParser.buildSinaOverview("broken;also broken"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("新浪指数数据为空");
    }

    @DisplayName("buildFinancials组装")
    @Test
    void givenFinancialRawJson_whenBuildFinancials_thenAssembleIndicators() {
        var f = MarketDataParser.buildFinancials("600519", "贵州茅台", 21.35, null, json(
                "{\"result\":{\"data\":[{\"REPORT_DATE\":\"2026-06-30 00:00:00\",\"EPSJB\":2.0}]}}"));
        assertThat(f.code()).isEqualTo("600519");
        assertThat(f.pe()).isEqualTo(21.35);
        assertThat(f.indicators()).hasSize(1);
        assertThat(f.indicators().get(0).reportDate()).isEqualTo("2026-06-30");
    }

    // ———— StockRef ————

    @DisplayName("stockRef后缀与北交所前缀")
    @Test
    void givenVariousCodes_whenStockRefFrom_thenInferSuffixAndBeijingPrefix() {
        assertThat(StockRef.from("600519.sh").secuCode()).isEqualTo("600519.SH");
        assertThat(StockRef.from("sz000001").market()).isEqualTo("0");
        assertThat(StockRef.from("bj430047").sinaPrefix()).isEqualTo("bj");
        assertThat(StockRef.from("430047").secuCode()).isEqualTo("430047.BJ");
        assertThat(StockRef.from("830047").secuCode()).isEqualTo("830047.BJ");
        assertThat(StockRef.from("900001").secuCode()).isEqualTo("900001.SH");
        assertThat(StockRef.from("300750").secuCode()).isEqualTo("300750.SZ");
    }

    @DisplayName("stockRef深京后缀剥离")
    @Test
    void givenSzBjSuffixedCode_whenStockRefFrom_thenStripSuffix() {
        assertThat(StockRef.from("000001.SZ").code()).isEqualTo("000001");
        assertThat(StockRef.from("430047.BJ").sinaPrefix()).isEqualTo("bj");
        assertThat(StockRef.from("430047.BJ").secuCode()).isEqualTo("430047.BJ");
    }

    @DisplayName("stockRef大小写与空格规范化")
    @Test
    void givenCodeWithCaseAndSpaces_whenStockRefFrom_thenNormalize() {
        assertThat(StockRef.from(" SH600519 ").code()).isEqualTo("600519");
        assertThat(StockRef.from("SZ000001").code()).isEqualTo("000001");
    }

    @DisplayName("stockRef非法输入抛INVALID_CODE")
    @Test
    void givenInvalidStockCode_whenStockRefFrom_thenThrowInvalidCode() {
        assertThatThrownBy(() -> StockRef.from("abc"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("无效的股票代码")
                .extracting(e -> ((MarketDataException) e).getCode())
                .isEqualTo(MarketDataErrorCode.INVALID_CODE);
        assertThatThrownBy(() -> StockRef.from(null))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("无效的股票代码");
        assertThatThrownBy(() -> StockRef.from("12345"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("无效的股票代码");
    }
}
