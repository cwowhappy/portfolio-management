package com.portfolio.invest.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.Charset;
import org.junit.jupiter.api.BeforeEach;
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

    @Test
    void rawQuote按GBK解码返回原始行() {
        String line = "var hq_str_sh600519=\"贵州茅台,1410.000,...\";";
        server.expect(requestTo(startsWith("https://hq.sinajs.cn/list=sh600519")))
                .andRespond(withSuccess(line.getBytes(GBK), MediaType.TEXT_PLAIN));
        assertThat(client.rawQuote("sh", "600519")).isEqualTo(line);
        server.verify();
    }

    @Test
    void rawIndices返回原始多行() {
        server.expect(requestTo(startsWith("https://hq.sinajs.cn/list=s_sh000001")))
                .andRespond(withSuccess("a;b;c".getBytes(GBK), MediaType.TEXT_PLAIN));
        assertThat(client.rawIndices()).isEqualTo("a;b;c");
        server.verify();
    }

    @Test
    void 空响应抛UPSTREAM_UNAVAILABLE且不重试() {
        String url = "https://hq.sinajs.cn/list=sh600519";
        server.expect(requestTo(startsWith(url)))
                .andRespond(withSuccess(new byte[0], MediaType.TEXT_PLAIN));
        assertThatThrownBy(() -> client.rawQuote("sh", "600519"))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("新浪接口返回空");
        server.verify();
    }

    @Test
    void 前两次失败第三次成功() {
        String url = "https://hq.sinajs.cn/list=sh600519";
        server.expect(requestTo(startsWith(url))).andRespond(withServerError());
        server.expect(requestTo(startsWith(url))).andRespond(withServerError());
        server.expect(requestTo(startsWith(url)))
                .andRespond(withSuccess("line".getBytes(GBK), MediaType.TEXT_PLAIN));
        assertThat(client.rawQuote("sh", "600519")).isEqualTo("line");
        server.verify();
    }

    @Test
    void 三次全失败抛UPSTREAM_UNAVAILABLE() {
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
