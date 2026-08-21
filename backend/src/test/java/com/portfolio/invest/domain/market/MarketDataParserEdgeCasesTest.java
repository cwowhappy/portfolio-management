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

    @Test
    void parseQuote数据缺失抛BAD_RESPONSE() {
        assertThatThrownBy(() -> MarketDataParser.parseQuote(json("{}")))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("行情数据为空");
        assertThatThrownBy(() -> MarketDataParser.parseQuote(json("{\"data\":null}")))
                .isInstanceOf(MarketDataException.class);
    }

    @Test
    void parseQuote价格无效抛BAD_RESPONSE() {
        assertThatThrownBy(() -> MarketDataParser.parseQuote(json("{\"data\":{\"f43\":0}}")))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("行情价格为空或无效");
    }

    @Test
    void parseQuote无时间戳时time为空() {
        Quote q = MarketDataParser.parseQuote(json(
                "{\"data\":{\"f43\":10,\"f57\":\"600519\",\"f58\":\"贵州茅台\",\"f86\":0}}"));
        assertThat(q.time()).isEmpty();
        assertThat(q.volume()).isZero();
        assertThat(q.pe()).isNull();
    }

    @Test
    void parseQuote异常值字符串视为空() {
        Quote q = MarketDataParser.parseQuote(json(
                "{\"data\":{\"f43\":10,\"f57\":\"600519\",\"f58\":\"贵州茅台\",\"f162\":\"-\",\"f167\":\"-\"}}"));
        assertThat(q.pe()).isNull();
        assertThat(q.pb()).isNull();
    }

    // ———— normalizeVolume ————

    @Test
    void normalizeVolume零值直接返回() {
        assertThat(MarketDataParser.normalizeVolume(0, 100, 10)).isZero();
        assertThat(MarketDataParser.normalizeVolume(100, 0, 10)).isEqualTo(100);
        assertThat(MarketDataParser.normalizeVolume(100, 100, 0)).isEqualTo(100);
    }

    @Test
    void normalizeVolume按手转股() {
        // raw=100 手, amount/price=10000 股 → 转股
        assertThat(MarketDataParser.normalizeVolume(100, 100_000, 10)).isEqualTo(10_000);
    }

    // ———— parseSinaQuote ————

    @Test
    void parseSinaQuote格式异常抛BAD_RESPONSE() {
        assertThatThrownBy(() -> MarketDataParser.parseSinaQuote("no quotes", "600519"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("新浪行情响应格式异常");
        assertThatThrownBy(() -> MarketDataParser.parseSinaQuote(
                "var hq=\"贵州茅台,1,2\";", "600519"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("新浪行情字段不足");
    }

    @Test
    void parseSinaQuote价格无效抛BAD_RESPONSE() {
        assertThatThrownBy(() -> MarketDataParser.parseSinaQuote(
                "var hq=\"贵州茅台,1,2,0,0,0,0,0,0,0\";", "600519"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("新浪行情价格无效");
    }

    @Test
    void parseSinaQuote十字段无时间() {
        // 10 个字段：有成交量、无时间（f.length<=31）
        Quote q = MarketDataParser.parseSinaQuote(
                "var hq=\"贵州茅台,10,9,10.5,11,10.8,10.2,10.5,12345,100000\";", "600519");
        assertThat(q.price()).isEqualTo(10.5);
        assertThat(q.volume()).isEqualTo(12345);
        assertThat(q.time()).isEmpty();
    }

    // ———— parseKline ————

    @Test
    void parseKline数据缺失抛BAD_RESPONSE() {
        assertThatThrownBy(() -> MarketDataParser.parseKline(json("{}")))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("K线数据为空");
    }

    @Test
    void parseKline行字段不足被跳过并整体为空时抛异常() {
        assertThatThrownBy(() -> MarketDataParser.parseKline(json(
                "{\"data\":{\"klines\":[\"2026-08-18,1,2,3\"]}}")))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("K线数据为空");
    }

    // ———— parseTencentKline ————

    @Test
    void parseTencentKline缺qfq前缀时回退裸周期键() {
        List<com.portfolio.invest.domain.market.KlineBar> bars = MarketDataParser.parseTencentKline(
                json("{\"data\":{\"sh600519\":{\"day\":[[\"2026-08-18\",\"1\",\"2\",\"3\",\"1\",\"100\"]]}}}"),
                "sh600519", "day");
        assertThat(bars).hasSize(1);
        assertThat(bars.get(0).volume()).isEqualTo(10_000); // 手 → 股
    }

    @Test
    void parseTencentKline数据为空抛BAD_RESPONSE() {
        assertThatThrownBy(() -> MarketDataParser.parseTencentKline(
                json("{\"data\":{\"sh600519\":{}}}"), "sh600519", "day"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("腾讯K线数据为空");
    }

    @Test
    void parseTencentKline短行跳过并按日期升序排序() {
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

    @Test
    void parseSearch数据非数组返回空() {
        assertThat(MarketDataParser.parseSearch(json("{\"QuotationCodeTable\":{}}"))).isEmpty();
    }

    @Test
    void parseSearch过滤各类非A股条目() {
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

    @Test
    void parseSearch市场号1的非沪深代码按沪市() {
        List<StockHit> hits = MarketDataParser.parseSearch(json(
                "{\"QuotationCodeTable\":{\"Data\":[{\"Code\":\"700001\",\"Name\":\"X\",\"MktNum\":\"1\"}]}}"));
        assertThat(hits.get(0).marketName()).isEqualTo("沪市");
    }

    // ———— 财务/新闻/指数 ————

    @Test
    void parseFinancialIndicators数据缺失抛BAD_RESPONSE() {
        assertThatThrownBy(() -> MarketDataParser.parseFinancialIndicators(json("{}")))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("财务数据为空");
    }

    @Test
    void parseNews数据非数组返回空() {
        assertThat(MarketDataParser.parseNews(json("{\"result\":{}}"))).isEmpty();
    }

    @Test
    void parseOverview数据非数组返回空() {
        assertThat(MarketDataParser.parseOverview(json("{\"data\":{}}"))).isEmpty();
    }

    @Test
    void buildOverview空数据抛BAD_RESPONSE() {
        assertThatThrownBy(() -> MarketDataParser.buildOverview(json("{\"data\":{}}")))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("指数数据为空");
    }

    @Test
    void buildSinaOverview坏行跳过() {
        var o = MarketDataParser.buildSinaOverview(
                "broken;"
                        + "var hq_str_s_sh000001=\"上证指数,3990.30,7.65,0.19,1,2\";"
                        + "var hq_str_s_sz399001=\"深证成指,14622.50,-81.77,-0.56,1,2\";");
        assertThat(o.indices()).hasSize(2);
        assertThat(o.indices().get(0).name()).isEqualTo("上证指数");
    }

    @Test
    void buildSinaOverview全坏行抛BAD_RESPONSE() {
        assertThatThrownBy(() -> MarketDataParser.buildSinaOverview("broken;also broken"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("新浪指数数据为空");
    }

    @Test
    void buildFinancials组装() {
        var f = MarketDataParser.buildFinancials("600519", "贵州茅台", 21.35, null, json(
                "{\"result\":{\"data\":[{\"REPORT_DATE\":\"2026-06-30 00:00:00\",\"EPSJB\":2.0}]}}"));
        assertThat(f.code()).isEqualTo("600519");
        assertThat(f.pe()).isEqualTo(21.35);
        assertThat(f.indicators()).hasSize(1);
        assertThat(f.indicators().get(0).reportDate()).isEqualTo("2026-06-30");
    }

    // ———— StockRef ————

    @Test
    void stockRef后缀与北交所前缀() {
        assertThat(StockRef.from("600519.sh").secuCode()).isEqualTo("600519.SH");
        assertThat(StockRef.from("sz000001").market()).isEqualTo("0");
        assertThat(StockRef.from("bj430047").sinaPrefix()).isEqualTo("bj");
        assertThat(StockRef.from("430047").secuCode()).isEqualTo("430047.BJ");
        assertThat(StockRef.from("830047").secuCode()).isEqualTo("830047.BJ");
        assertThat(StockRef.from("900001").secuCode()).isEqualTo("900001.SH");
        assertThat(StockRef.from("300750").secuCode()).isEqualTo("300750.SZ");
    }

    @Test
    void stockRef大小写与空格规范化() {
        assertThat(StockRef.from(" SH600519 ").code()).isEqualTo("600519");
        assertThat(StockRef.from("SZ000001").code()).isEqualTo("000001");
    }

    @Test
    void stockRef非法输入抛INVALID_CODE() {
        assertThatThrownBy(() -> StockRef.from("abc"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("无效的股票代码")
                .extracting(e -> ((MarketDataException) e).getCode())
                .isEqualTo("INVALID_CODE");
        assertThatThrownBy(() -> StockRef.from(null))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("无效的股票代码");
        assertThatThrownBy(() -> StockRef.from("12345"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("无效的股票代码");
    }
}
