package com.portfolio.invest.infrastructure.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.invest.domain.market.MarketDataException;
import com.portfolio.invest.domain.market.MarketOverview;
import com.portfolio.invest.domain.market.Quote;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 新浪 wire format 解析测试（2026-10-16 L2-二期下沉承接原 domain 侧文本用例）：引号提取、
 * 逗号字段序、涨跌回算（round2）、变量名指数代码提取、坏行跳过的断言自
 * {@code MarketDataParserTest}/{@code MarketDataParserEdgeCasesTest} 平移至此，
 * 断言语义与下沉前逐字一致（callee 由 domain 静态方法改为本类）。
 */
class SinaFormatTest {

    private static String fixtureText(String name) throws IOException {
        try (InputStream in = SinaFormatTest.class.getResourceAsStream("/fixtures/" + name)) {
            assert in != null : "fixture not found: " + name;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ———— 行情行 ————

    @DisplayName("新浪行情文本解析出报价")
    @Test
    void givenSinaRawText_whenParseQuote_thenReturnQuote() throws IOException {
        Quote q = SinaFormat.parseQuote(fixtureText("sina-quote.txt"), "600519");
        assertThat(q.name()).isEqualTo("贵州茅台");
        assertThat(q.price()).isEqualTo(1415.0);
        assertThat(q.prevClose()).isEqualTo(1400.0);
        assertThat(q.changePct()).isEqualTo(1.07);
        assertThat(q.time()).isEqualTo("2026-08-18 14:35:00");
    }

    @DisplayName("parseQuote只有一个引号视为格式异常")
    @Test
    void givenSinaQuoteWithOnlyOpeningQuote_whenParseQuote_thenFormatError() {
        // 有起始引号但无收尾引号：end <= start
        assertThatThrownBy(() -> SinaFormat.parseQuote(
                "var hq=\"贵州茅台,1,2,3", "600519"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("新浪行情响应格式异常");
    }

    @DisplayName("parseQuote昨收为零时涨跌幅归零避免除零")
    @Test
    void givenPrevCloseZero_whenParseQuote_thenChangePctZeroAvoidingDivByZero() {
        Quote q = SinaFormat.parseQuote(
                "var hq=\"贵州茅台,10,0,10.5,11,10.8,10.2,10.5,12345,100000\";", "600519");
        assertThat(q.change()).isEqualTo(10.5);
        assertThat(q.changePct()).isEqualTo(0);
    }

    @DisplayName("parseQuote格式异常抛BAD_RESPONSE")
    @Test
    void givenMalformedSinaQuote_whenParseQuote_thenThrowBadResponse() {
        assertThatThrownBy(() -> SinaFormat.parseQuote("no quotes", "600519"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("新浪行情响应格式异常");
        assertThatThrownBy(() -> SinaFormat.parseQuote(
                "var hq=\"贵州茅台,1,2\";", "600519"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("新浪行情字段不足");
    }

    @DisplayName("parseQuote价格无效抛BAD_RESPONSE")
    @Test
    void givenSinaQuotePriceInvalid_whenParseQuote_thenThrowBadResponse() {
        assertThatThrownBy(() -> SinaFormat.parseQuote(
                "var hq=\"贵州茅台,1,2,0,0,0,0,0,0,0\";", "600519"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("新浪行情价格无效");
    }

    @DisplayName("parseQuote十字段无时间")
    @Test
    void givenSinaQuoteWithTenFields_whenParseQuote_thenTimeEmpty() {
        // 10 个字段：有成交量、无时间（f.length<=31）
        Quote q = SinaFormat.parseQuote(
                "var hq=\"贵州茅台,10,9,10.5,11,10.8,10.2,10.5,12345,100000\";", "600519");
        assertThat(q.price()).isEqualTo(10.5);
        assertThat(q.volume()).isEqualTo(12345);
        assertThat(q.time()).isEmpty();
    }

    // ———— 指数行 ————

    @DisplayName("新浪指数文本构建出指数速览")
    @Test
    void givenSinaIndicesRaw_whenBuildOverview_thenReturnIndices() throws IOException {
        MarketOverview o = SinaFormat.buildOverview(fixtureText("sina-indices.txt"));
        assertThat(o.indices()).hasSize(3);
        assertThat(o.indices().get(0).name()).isEqualTo("上证指数");
        assertThat(o.indices().get(0).price()).isEqualTo(3990.30);
        assertThat(o.indices().get(1).changePct()).isEqualTo(-0.56);
    }

    @DisplayName("buildOverview单边引号与字段不足行被跳过")
    @Test
    void givenUnbalancedQuoteAndShortRows_whenBuildOverview_thenSkipRows() {
        var o = SinaFormat.buildOverview(
                "var hq_str_s_sh000001=\"上证指数,3990.30,7.65,0.19\";"
                        + "var hq_str_s_sz399006=\"创业板指,3200.00\";" // 字段不足 4 列 → 跳过
                        + "var hq_str_s_sz399001=\"深证成指,14622.50,-81.77,-0.56"); // 无收尾引号 → 跳过
        assertThat(o.indices()).hasSize(1);
        assertThat(o.indices().get(0).code()).isEqualTo("000001");
    }

    @DisplayName("buildOverview变量名无等号或无下划线时代码退化提取")
    @Test
    void givenVarWithoutEqualsOrUnderscore_whenBuildOverview_thenDegradeCodeExtraction() {
        var o = SinaFormat.buildOverview(
                "\"1,2,3,4\";"                    // 无 '=' → 代码为空串
                        + "var x_12=\"A,1,2,3\";"        // 下划线后 id 仅 2 位 → 原样返回
                        + "var hq=\"B,1,2,3\";");        // 无下划线 → 整个变量名退化提取
        assertThat(o.indices()).hasSize(3);
        assertThat(o.indices().get(0).code()).isEmpty();
        assertThat(o.indices().get(1).code()).isEqualTo("12");
        assertThat(o.indices().get(2).code()).isEqualTo("r hq");
    }

    @DisplayName("buildOverview坏行跳过")
    @Test
    void givenBadRows_whenBuildOverview_thenSkipThem() {
        var o = SinaFormat.buildOverview(
                "broken;"
                        + "var hq_str_s_sh000001=\"上证指数,3990.30,7.65,0.19,1,2\";"
                        + "var hq_str_s_sz399001=\"深证成指,14622.50,-81.77,-0.56,1,2\";");
        assertThat(o.indices()).hasSize(2);
        assertThat(o.indices().get(0).name()).isEqualTo("上证指数");
    }

    @DisplayName("buildOverview全坏行抛BAD_RESPONSE")
    @Test
    void givenAllBadRows_whenBuildOverview_thenThrowBadResponse() {
        assertThatThrownBy(() -> SinaFormat.buildOverview("broken;also broken"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("新浪指数数据为空");
    }
}
