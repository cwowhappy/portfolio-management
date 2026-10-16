package com.portfolio.invest.infrastructure.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.domain.market.KlineBar;
import com.portfolio.invest.domain.market.MarketDataException;
import com.portfolio.invest.domain.market.MarketOverview;
import com.portfolio.invest.domain.market.Quote;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 腾讯 wire format 解析测试（2026-10-16 L2-二期下沉承接原 domain 侧用例）：~ 分隔行情行
 * （47 字段序/手→股/万元→元/PE(TTM)/PB/时间戳）、指数行与 data.{symbol}.{qfq+period} K线
 * 导航的断言自 {@code MarketDataParserTest}/{@code MarketDataParserEdgeCasesTest} 平移至此，
 * 断言语义与下沉前逐字一致（callee 由 domain 静态方法改为本类）。
 */
class TencentFormatTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String s) {
        try {
            return mapper.readTree(s);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static JsonNode fixture(String name) throws IOException {
        try (InputStream in = TencentFormatTest.class.getResourceAsStream("/fixtures/" + name)) {
            assert in != null : "fixture not found: " + name;
            return new ObjectMapper().readTree(in);
        }
    }

    private static String fixtureText(String name) throws IOException {
        try (InputStream in = TencentFormatTest.class.getResourceAsStream("/fixtures/" + name)) {
            assert in != null : "fixture not found: " + name;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ———— 行情行 ————

    @DisplayName("腾讯行情文本解析出报价")
    @Test
    void givenTencentRawText_whenParseQuote_thenReturnQuote() throws IOException {
        Quote q = TencentFormat.parseQuote(fixtureText("tencent-quote.txt"), "600519");
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

    @DisplayName("parseQuote无引号抛BAD_RESPONSE")
    @Test
    void givenNoQuotes_whenParseQuote_thenThrowBadResponse() {
        assertThatThrownBy(() -> TencentFormat.parseQuote("garbage", "600519"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("腾讯行情响应格式异常");
    }

    @DisplayName("parseQuote字段不足抛BAD_RESPONSE")
    @Test
    void givenTooFewFields_whenParseQuote_thenThrowBadResponse() {
        assertThatThrownBy(() -> TencentFormat.parseQuote("v_sh600519=\"1~贵州茅台~600519\"", "600519"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("腾讯行情字段不足");
    }

    @DisplayName("parseQuote价格非正抛BAD_RESPONSE")
    @Test
    void givenNonPositivePrice_whenParseQuote_thenThrowBadResponse() {
        String raw = tencentQuoteWith(f -> { f[3] = "0.00"; return f; });
        assertThatThrownBy(() -> TencentFormat.parseQuote(raw, "600519"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("腾讯行情价格无效");
    }

    @DisplayName("parseQuote时间戳空、畸形或语义非法返回空串")
    @Test
    void givenBlankOrMalformedTime_whenParseQuote_thenTimeEmpty() {
        assertThat(TencentFormat.parseQuote(
                tencentQuoteWith(f -> { f[30] = ""; return f; }), "600519").time()).isEmpty();
        assertThat(TencentFormat.parseQuote(
                tencentQuoteWith(f -> { f[30] = "abc"; return f; }), "600519").time()).isEmpty();
        // 14 位但语义非法（月 99）：LocalDateTime.parse 抛 DateTimeParseException，
        // 须与畸形同口径返回空串，否则穿透 withFallback（只捕 MarketDataException）直达 500（issue #53）
        assertThat(TencentFormat.parseQuote(
                tencentQuoteWith(f -> { f[30] = "99999999999999"; return f; }), "600519").time()).isEmpty();
    }

    @DisplayName("parseQuote空PE/PB返回null")
    @Test
    void givenEmptyValuation_whenParseQuote_thenNullPePb() {
        Quote q = TencentFormat.parseQuote(
                tencentQuoteWith(f -> { f[39] = ""; f[46] = ""; return f; }), "600519");
        assertThat(q.pe()).isNull();
        assertThat(q.pb()).isNull();
    }

    // ———— 指数行 ————

    @DisplayName("腾讯指数文本解析出三指数速览")
    @Test
    void givenTencentIndicesRaw_whenBuildOverview_thenReturnThreeIndices() throws IOException {
        MarketOverview o = TencentFormat.buildOverview(fixtureText("tencent-indices.txt"));
        assertThat(o.indices()).hasSize(3);
        assertThat(o.indices().get(0).code()).isEqualTo("000001");
        assertThat(o.indices().get(0).name()).isEqualTo("上证指数");
        assertThat(o.indices().get(0).price()).isEqualTo(3888.37);
        assertThat(o.indices().get(0).change()).isEqualTo(-48.15);
        assertThat(o.indices().get(0).changePct()).isEqualTo(-1.22);
        assertThat(o.time()).isNotBlank();
    }

    @DisplayName("buildOverview无有效行抛BAD_RESPONSE")
    @Test
    void givenNoValidLines_whenBuildOverview_thenThrowBadResponse() {
        assertThatThrownBy(() -> TencentFormat.buildOverview("garbage"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("腾讯指数数据为空");
    }

    // ———— K线帧 ————

    @DisplayName("parseKlineJSON解析出K线柱")
    @Test
    void givenTencentKlineJson_whenParseKline_thenReturnBars() throws IOException {
        List<KlineBar> bars = TencentFormat.parseKline(
                fixture("tencent-kline.json"), "sh600519", "day");
        assertThat(bars).hasSize(3);
        assertThat(bars.get(0).date()).isEqualTo("2026-08-14");
        assertThat(bars.get(2).close()).isEqualTo(1297.99);
        assertThat(bars.get(2).volume()).isEqualTo(3872300L); // 手 → 股
        assertThat(bars.get(2).amount()).isEqualTo(0.0); // 腾讯无成交额字段
        assertThat(bars.get(2).amplitudePct()).isEqualTo(0.0); // 腾讯无振幅字段
    }

    @DisplayName("parseKlineqfq键非数组时回退裸周期键")
    @Test
    void givenQfqKeyNonArray_whenParseKline_thenFallbackToBarePeriodKey() {
        List<KlineBar> bars = TencentFormat.parseKline(
                json("{\"data\":{\"sh600519\":{\"qfqday\":{\"unexpected\":true},\"day\":"
                        + "[[\"2026-08-18\",\"1\",\"2\",\"3\",\"1\",\"100\"]]}}}"),
                "sh600519", "day");
        assertThat(bars).hasSize(1);
        assertThat(bars.get(0).volume()).isEqualTo(10_000);
    }

    @DisplayName("parseKline非数组行跳过且全部无效时抛BAD_RESPONSE")
    @Test
    void givenAllTencentRowsNonArray_whenParseKline_thenThrowBadResponse() {
        assertThatThrownBy(() -> TencentFormat.parseKline(
                json("{\"data\":{\"sh600519\":{\"qfqday\":[\"not-an-array\"]}}}"), "sh600519", "day"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("腾讯K线数据为空");
    }

    @DisplayName("parseKline缺qfq前缀时回退裸周期键")
    @Test
    void givenQfqPrefixMissing_whenParseKline_thenFallbackToBarePeriodKey() {
        List<KlineBar> bars = TencentFormat.parseKline(
                json("{\"data\":{\"sh600519\":{\"day\":[[\"2026-08-18\",\"1\",\"2\",\"3\",\"1\",\"100\"]]}}}"),
                "sh600519", "day");
        assertThat(bars).hasSize(1);
        assertThat(bars.get(0).volume()).isEqualTo(10_000); // 手 → 股
    }

    @DisplayName("parseKline数据为空抛BAD_RESPONSE")
    @Test
    void givenTencentDataEmpty_whenParseKline_thenThrowBadResponse() {
        assertThatThrownBy(() -> TencentFormat.parseKline(
                json("{\"data\":{\"sh600519\":{}}}"), "sh600519", "day"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("腾讯K线数据为空");
    }

    @DisplayName("parseKline短行跳过并按日期升序排序")
    @Test
    void givenShortAndValidRows_whenParseKline_thenSkipShortsAndSortAscending() {
        List<KlineBar> bars = TencentFormat.parseKline(
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
}
