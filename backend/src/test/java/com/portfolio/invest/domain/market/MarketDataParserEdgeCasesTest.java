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
 * domain 层的空列表/空值容忍口径（Raw 入参）；新浪/腾讯文本兜底分支已平移至
 * {@code SinaFormatTest}/{@code TencentFormatTest}（2026-10-16 L2-二期）。
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
