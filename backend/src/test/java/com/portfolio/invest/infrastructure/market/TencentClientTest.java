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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** 腾讯 K线兜底客户端。 */
class TencentClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private MockRestServiceServer server;
    private TencentClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TencentClient(builder.build());
    }

    private String fixture(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @DisplayName("kline返回原始JSON")
    @Test
    void klineReturnsRawJson() throws IOException {
        server.expect(requestTo(startsWith("https://web.ifzq.gtimg.cn/appstock/app/fqkline/get")))
                .andRespond(withSuccess(fixture("tencent-kline.json"), MediaType.APPLICATION_JSON));
        JsonNode node = client.kline("sh600519", "day", 120);
        assertThat(node.path("data").path("sh600519").path("qfqday")).isNotEmpty();
        server.verify();
    }

    @DisplayName("周期映射与默认值")
    @Test
    void periodMappingAndDefaults() throws IOException {
        String url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get";
        server.expect(requestTo(startsWith(url))).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(url))).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(url))).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        client.kline("sh600519", "week", 10);
        client.kline("sh600519", "month", 10);
        client.kline("sh600519", "whatever", 10);
        server.verify();
    }

    @DisplayName("三次全失败抛UPSTREAM_UNAVAILABLE")
    @Test
    void throwsUpstreamUnavailableAfterThreeFailures() {
        String url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get";
        // 腾讯与东财/新浪一致：三次尝试均失败后包装为领域异常
        server.expect(requestTo(startsWith(url))).andRespond(withServerError());
        server.expect(requestTo(startsWith(url))).andRespond(withServerError());
        server.expect(requestTo(startsWith(url))).andRespond(withServerError());
        assertThatThrownBy(() -> client.kline("sh600519", "day", 120))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("腾讯K线接口不可用");
        server.verify();
    }
}
