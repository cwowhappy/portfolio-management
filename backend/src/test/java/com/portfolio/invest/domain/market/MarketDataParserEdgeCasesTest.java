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
import com.portfolio.invest.infrastructure.market.EastmoneyFormat;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MarketDataParser / StockRef 的边界与异常分支。东财 JSON 帧的 wire 边界（klines/result.data
 * 缺失、非法形态）已平移至 {@code EastmoneyFormatTest}（2026-10-15 L2 下沉），本类保留
 * domain 层的空列表/空值容忍口径（Raw 入参）与新浪/腾讯文本兜底分支（L2-二期）。
 */
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
        assertThatThrownBy(() -> EastmoneyFormat.parseQuote(json("{}")))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("行情数据为空");
        assertThatThrownBy(() -> EastmoneyFormat.parseQuote(json("{\"data\":null}")))
                .isInstanceOf(MarketDataException.class);
    }

    @DisplayName("parseQuote价格无效抛BAD_RESPONSE")
    @Test
    void givenQuotePriceInvalid_whenParseQuote_thenThrowBadResponse() {
        assertThatThrownBy(() -> MarketDataParser.parseQuote(
                        EastmoneyFormat.extractQuote(json("{\"data\":{\"f43\":0}}"))))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("行情价格为空或无效");
    }

    @DisplayName("parseQuote无时间戳时time为空")
    @Test
    void givenQuoteWithoutTimestamp_whenParseQuote_thenTimeEmpty() {
        Quote q = EastmoneyFormat.parseQuote(json(
                "{\"data\":{\"f43\":10,\"f57\":\"600519\",\"f58\":\"贵州茅台\",\"f86\":0}}"));
        assertThat(q.time()).isEmpty();
        assertThat(q.volume()).isZero();
        assertThat(q.pe()).isNull();
    }

    @DisplayName("parseQuote异常值字符串视为空")
    @Test
    void givenQuoteDashValueStrings_whenParseQuote_thenTreatedAsEmpty() {
        Quote q = EastmoneyFormat.parseQuote(json(
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

    // ———— parseTencentQuote / buildTencentOverview ————

    /** 构造 47 位腾讯行情行，指定位置可覆盖。 */
    private String tencentQuoteWith(java.util.function.UnaryOperator<String[]> mutator) {
        String[] f = new String[47];
        java.util.Arrays.fill(f, "");
        f[1] = "贵州茅台"; f[2] = "600519"; f[3] = "1237.00"; f[4] = "1251.24"; f[5] = "1250.01";
        f[6] = "31239"; f[30] = "20260924161444"; f[31] = "-14.24"; f[32] = "-1.14";
        f[33] = "1256.13"; f[34] = "1231.05"; f[37] = "386731"; f[39] = "18.99"; f[46] = "6.15";
        f = mutator.apply(f);
        return "v_sh600519=\"" + String.join("~", f) + "\";";
    }

    @DisplayName("parseTencentQuote无引号抛BAD_RESPONSE")
    @Test
    void givenNoQuotes_whenParseTencentQuote_thenThrowBadResponse() {
        assertThatThrownBy(() -> MarketDataParser.parseTencentQuote("garbage", "600519"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("腾讯行情响应格式异常");
    }

    @DisplayName("parseTencentQuote字段不足抛BAD_RESPONSE")
    @Test
    void givenTooFewFields_whenParseTencentQuote_thenThrowBadResponse() {
        assertThatThrownBy(() -> MarketDataParser.parseTencentQuote("v_sh600519=\"1~贵州茅台~600519\"", "600519"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("腾讯行情字段不足");
    }

    @DisplayName("parseTencentQuote价格非正抛BAD_RESPONSE")
    @Test
    void givenNonPositivePrice_whenParseTencentQuote_thenThrowBadResponse() {
        String raw = tencentQuoteWith(f -> { f[3] = "0.00"; return f; });
        assertThatThrownBy(() -> MarketDataParser.parseTencentQuote(raw, "600519"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("腾讯行情价格无效");
    }

    @DisplayName("parseTencentQuote时间戳空、畸形或语义非法返回空串")
    @Test
    void givenBlankOrMalformedTime_whenParseTencentQuote_thenTimeEmpty() {
        assertThat(MarketDataParser.parseTencentQuote(
                tencentQuoteWith(f -> { f[30] = ""; return f; }), "600519").time()).isEmpty();
        assertThat(MarketDataParser.parseTencentQuote(
                tencentQuoteWith(f -> { f[30] = "abc"; return f; }), "600519").time()).isEmpty();
        // 14 位但语义非法（月 99）：LocalDateTime.parse 抛 DateTimeParseException，
        // 须与畸形同口径返回空串，否则穿透 withFallback（只捕 MarketDataException）直达 500（issue #53）
        assertThat(MarketDataParser.parseTencentQuote(
                tencentQuoteWith(f -> { f[30] = "99999999999999"; return f; }), "600519").time()).isEmpty();
    }

    @DisplayName("parseTencentQuote空PE/PB返回null")
    @Test
    void givenEmptyValuation_whenParseTencentQuote_thenNullPePb() {
        Quote q = MarketDataParser.parseTencentQuote(
                tencentQuoteWith(f -> { f[39] = ""; f[46] = ""; return f; }), "600519");
        assertThat(q.pe()).isNull();
        assertThat(q.pb()).isNull();
    }

    @DisplayName("buildTencentOverview无有效行抛BAD_RESPONSE")
    @Test
    void givenNoValidLines_whenBuildTencentOverview_thenThrowBadResponse() {
        assertThatThrownBy(() -> MarketDataParser.buildTencentOverview("garbage"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("腾讯指数数据为空");
    }

    // ———— parseKline（Raw 入参的空校验口径） ————

    @DisplayName("parseKline空列表抛BAD_RESPONSE（缺失/全跳过口径下沉后由domain保持）")
    @Test
    void givenEmptyKlineRaws_whenParseKline_thenThrowBadResponse() {
        assertThatThrownBy(() -> MarketDataParser.parseKline(List.of()))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("K线数据为空");
        assertThatThrownBy(() -> MarketDataParser.parseKline(null))
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
        List<StockHit> hits = MarketDataParser.parseSearch(List.of(
                new RawStockHit("430047", "诺思兰德", "0", null),
                new RawStockHit("830047", "某北证", "0", null),
                new RawStockHit("900001", "某沪B", "1", null)));
        assertThat(hits).extracting(StockHit::marketName)
                .containsExactly("北交所", "北交所", "沪市");
    }

    @DisplayName("parseSearch空入参容忍为空（缺失节点口径下沉后由domain保持）")
    @Test
    void givenNullRaws_whenParseSearch_thenReturnEmpty() {
        assertThat(MarketDataParser.parseSearch(null)).isEmpty();
    }

    @DisplayName("parseSearch市场号1的非沪深代码按沪市")
    @Test
    void givenMktNum1CodeBeyondShSz_whenParseSearch_thenMapToShanghai() {
        List<StockHit> hits = MarketDataParser.parseSearch(
                List.of(new RawStockHit("700001", "X", "1", null)));
        assertThat(hits.get(0).marketName()).isEqualTo("沪市");
    }

    // ———— 财务/新闻/指数（Raw 入参口径） ————

    @DisplayName("parseFinancialIndicators空入参容忍为空（缺失节点口径下沉后由domain保持）")
    @Test
    void givenNullRaws_whenParseFinancialIndicators_thenReturnEmpty() {
        assertThat(MarketDataParser.parseFinancialIndicators(null)).isEmpty();
    }

    @DisplayName("parseNews长摘要截断为120字加省略号")
    @Test
    void givenLongNewsSummary_whenParseNews_thenTruncateTo120CharsWithEllipsis() {
        String longContent = "长".repeat(200);
        var list = MarketDataParser.parseNews(List.of(
                new RawNewsItem("t", longContent, "m", "d", "u")));
        assertThat(list.get(0).summary()).hasSize(121).endsWith("…");
    }

    @DisplayName("parseNews空入参容忍为空（缺失节点口径下沉后由domain保持）")
    @Test
    void givenNullRaws_whenParseNews_thenReturnEmpty() {
        assertThat(MarketDataParser.parseNews(null)).isEmpty();
    }

    @DisplayName("parseOverview空入参容忍为空（缺失节点口径下沉后由domain保持）")
    @Test
    void givenNullRaws_whenParseOverview_thenReturnEmpty() {
        assertThat(MarketDataParser.parseOverview(null)).isEmpty();
    }

    @DisplayName("buildOverview空数据抛BAD_RESPONSE")
    @Test
    void givenEmptyRaws_whenBuildOverview_thenThrowBadResponse() {
        assertThatThrownBy(() -> MarketDataParser.buildOverview(List.of()))
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
    void givenFinancialRaws_whenBuildFinancials_thenAssembleIndicators() {
        var f = MarketDataParser.buildFinancials("600519", "贵州茅台", 21.35, null, List.of(
                new RawFinancialIndicator("2026-06-30 00:00:00", 2.0, null, null, null, null, null)));
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
