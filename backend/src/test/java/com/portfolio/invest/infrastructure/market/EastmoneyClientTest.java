package com.portfolio.invest.infrastructure.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.domain.market.MarketDataException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** 东财客户端：URL 构造、JSONP 解包、重试与错误包装。 */
class EastmoneyClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private MockRestServiceServer server;
    private EastmoneyClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new EastmoneyClient(builder.build());
    }

    private String fixture(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void quote返回原始JSON() throws IOException {
        server.expect(requestTo(startsWith("https://push2.eastmoney.com/api/qt/stock/get")))
                .andRespond(withSuccess(fixture("eastmoney-quote.json"), MediaType.APPLICATION_JSON));
        JsonNode node = client.quote("1.600519");
        assertThat(node.path("data").path("f57").asText()).isEqualTo("600519");
        server.verify();
    }

    @Test
    void kline返回原始JSON() throws IOException {
        server.expect(requestTo(startsWith("https://push2his.eastmoney.com/api/qt/stock/kline/get")))
                .andRespond(withSuccess(fixture("eastmoney-kline.json"), MediaType.APPLICATION_JSON));
        assertThat(client.kline("1.600519", 101, 120).path("data").path("klines")).isNotEmpty();
        server.verify();
    }

    @Test
    void search编码关键词() throws IOException {
        server.expect(requestTo(startsWith("https://searchapi.eastmoney.com/api/suggest/get")))
                .andRespond(withSuccess(fixture("eastmoney-search.json"), MediaType.APPLICATION_JSON));
        JsonNode node = client.search("茅台");
        assertThat(node.path("QuotationCodeTable").path("Data")).isNotEmpty();
        server.verify();
    }

    @Test
    void financials与overview返回原始JSON() throws IOException {
        server.expect(requestTo(startsWith("https://datacenter.eastmoney.com/securities/api/data/v1/get")))
                .andRespond(withSuccess(fixture("eastmoney-financials.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith("https://push2.eastmoney.com/api/qt/ulist.np/get")))
                .andRespond(withSuccess(fixture("eastmoney-overview.json"), MediaType.APPLICATION_JSON));
        assertThat(client.financials("600519.SH").path("result").path("data")).isNotEmpty();
        assertThat(client.overview().path("data").path("diff")).isNotEmpty();
        server.verify();
    }

    @Test
    void news解包JSONP响应() throws IOException {
        server.expect(requestTo(startsWith("https://search-api-web.eastmoney.com/search/jsonp")))
                .andRespond(withSuccess("cb(" + fixture("eastmoney-news.json") + ")", MediaType.APPLICATION_JSON));
        JsonNode node = client.news("贵州茅台", 10);
        assertThat(node.path("result").path("cmsArticleWebOld")).isNotEmpty();
        server.verify();
    }

    @Test
    void news响应缺少括号时报BAD_RESPONSE() {
        server.expect(requestTo(startsWith("https://search-api-web.eastmoney.com/search/jsonp")))
                .andRespond(withSuccess("oops-no-parens", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.news("贵州茅台", 10))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("新闻接口响应格式异常");
        server.verify();
    }

    @Test
    void news响应缺少收尾括号时报BAD_RESPONSE() {
        // 有 '(' 无 ')'：end <= start → JSONP 解包失败
        server.expect(requestTo(startsWith("https://search-api-web.eastmoney.com/search/jsonp")))
                .andRespond(withSuccess("cb({\"a\":1}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.news("贵州茅台", 10))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("新闻接口响应格式异常");
        server.verify();
    }

    @Test
    void 响应非JSON时报BAD_RESPONSE() {
        server.expect(requestTo(startsWith("https://push2.eastmoney.com/api/qt/stock/get")))
                .andRespond(withSuccess("<html>not json</html>", MediaType.TEXT_HTML));
        assertThatThrownBy(() -> client.quote("1.600519"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("行情接口响应解析失败");
        server.verify();
    }

    @Test
    void 前两次失败第三次成功() throws IOException {
        String url = "https://push2.eastmoney.com/api/qt/stock/get";
        server.expect(requestTo(startsWith(url))).andRespond(withServerError());
        server.expect(requestTo(startsWith(url))).andRespond(withServerError());
        server.expect(requestTo(startsWith(url)))
                .andRespond(withSuccess(fixture("eastmoney-quote.json"), MediaType.APPLICATION_JSON));
        JsonNode node = client.quote("1.600519");
        assertThat(node.path("data").path("f57").asText()).isEqualTo("600519");
        server.verify();
    }

    @Test
    void 三次全失败抛UPSTREAM_UNAVAILABLE() {
        String url = "https://push2.eastmoney.com/api/qt/stock/get";
        server.expect(requestTo(startsWith(url))).andRespond(withServerError());
        server.expect(requestTo(startsWith(url))).andRespond(withServerError());
        server.expect(requestTo(startsWith(url))).andRespond(withServerError());
        assertThatThrownBy(() -> client.quote("1.600519"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("东方财富接口不可用");
        server.verify();
    }
}
