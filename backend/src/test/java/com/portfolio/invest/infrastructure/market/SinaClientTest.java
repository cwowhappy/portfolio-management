package com.portfolio.invest.infrastructure.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.portfolio.invest.domain.market.MarketDataException;
import java.nio.charset.Charset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** 新浪兜底客户端：GBK 解码、空响应与重试。 */
class SinaClientTest {

    private static final Charset GBK = Charset.forName("GBK");

    private MockRestServiceServer server;
    private SinaClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new SinaClient(builder.build());
    }

    @DisplayName("rawQuote按GBK解码返回原始行")
    @Test
    void givenGbkResponse_whenRawQuote_thenDecodesAndReturnsRawLine() {
        String line = "var hq_str_sh600519=\"贵州茅台,1410.000,...\";";
        server.expect(requestTo(startsWith("https://hq.sinajs.cn/list=sh600519")))
                .andRespond(withSuccess(line.getBytes(GBK), MediaType.TEXT_PLAIN));
        assertThat(client.rawQuote("sh", "600519")).isEqualTo(line);
        server.verify();
    }

    @DisplayName("rawIndices返回原始多行")
    @Test
    void whenRawIndices_thenReturnsRawLines() {
        server.expect(requestTo(startsWith("https://hq.sinajs.cn/list=s_sh000001")))
                .andRespond(withSuccess("a;b;c".getBytes(GBK), MediaType.TEXT_PLAIN));
        assertThat(client.rawIndices()).isEqualTo("a;b;c");
        server.verify();
    }

    @DisplayName("空响应抛UPSTREAM_UNAVAILABLE且不重试")
    @Test
    void givenEmptyResponse_whenRawQuote_thenThrowsUpstreamUnavailableWithoutRetry() {
        String url = "https://hq.sinajs.cn/list=sh600519";
        server.expect(requestTo(startsWith(url)))
                .andRespond(withSuccess(new byte[0], MediaType.TEXT_PLAIN));
        assertThatThrownBy(() -> client.rawQuote("sh", "600519"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("新浪接口返回空");
        server.verify();
    }

    @DisplayName("无内容响应视为空响应抛UPSTREAM_UNAVAILABLE")
    @Test
    void givenNoContentResponse_whenRawQuote_thenThrowsUpstreamUnavailable() {
        // 204 No Content → body(byte[].class) 为 null，与空字节数组同等处理
        server.expect(requestTo(startsWith("https://hq.sinajs.cn/list=sh600519")))
                .andRespond(withNoContent());
        assertThatThrownBy(() -> client.rawQuote("sh", "600519"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("新浪接口返回空");
        server.verify();
    }

    @DisplayName("前两次失败第三次成功")
    @Test
    void givenTwoFailures_whenRawQuote_thenSucceedsOnThird() {
        String url = "https://hq.sinajs.cn/list=sh600519";
        server.expect(requestTo(startsWith(url))).andRespond(withServerError());
        server.expect(requestTo(startsWith(url))).andRespond(withServerError());
        server.expect(requestTo(startsWith(url)))
                .andRespond(withSuccess("line".getBytes(GBK), MediaType.TEXT_PLAIN));
        assertThat(client.rawQuote("sh", "600519")).isEqualTo("line");
        server.verify();
    }

    @DisplayName("三次全失败抛UPSTREAM_UNAVAILABLE")
    @Test
    void givenThreeFailures_whenRawQuote_thenThrowsUpstreamUnavailable() {
        String url = "https://hq.sinajs.cn/list=sh600519";
        server.expect(requestTo(startsWith(url))).andRespond(withServerError());
        server.expect(requestTo(startsWith(url))).andRespond(withServerError());
        server.expect(requestTo(startsWith(url))).andRespond(withServerError());
        assertThatThrownBy(() -> client.rawQuote("sh", "600519"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("新浪接口不可用");
        server.verify();
    }
}
