package com.portfolio.invest.infrastructure.im;

import com.portfolio.invest.config.InvestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FeishuClientTest {

    private static final String TOKEN_OK =
            "{\"code\":0,\"msg\":\"ok\",\"tenant_access_token\":\"t-abc\",\"expire\":7200}";

    private InvestProperties props;
    private MockRestServiceServer server;
    private FeishuClient client;

    @BeforeEach
    void setUp() {
        props = new InvestProperties();
        props.getIm().setAppId("cli_test");
        props.getIm().setAppSecret("secret_test");
        RestClient.Builder builder = RestClient.builder().baseUrl("https://open.feishu.cn");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new FeishuClient(props, builder.build());
    }

    private void expectToken() {
        server.expect(requestTo(startsWith("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(TOKEN_OK, MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("给定未缓存token，when发送卡片，then先取token再发消息且卡片结构正确")
    void given未缓存token_when发送_then两段请求且卡片正确() throws Exception {
        expectToken();
        server.expect(requestTo(startsWith("https://open.feishu.cn/open-apis/im/v1/messages")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer t-abc"))
                .andRespond(withSuccess("{\"code\":0,\"msg\":\"success\"}", MediaType.APPLICATION_JSON));
        boolean ok = client.sendCard("oc_x", "⚠️ 投资原则预警", "red", List.of("**交易日**：2026-09-26", "- 行1"));
        assertThat(ok).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("给定token已缓存未过期，when再次发送，then不再请求token端点")
    void given缓存token_when再次发送_then仅一次消息请求() {
        expectToken();
        server.expect(requestTo(startsWith("https://open.feishu.cn/open-apis/im/v1/messages")))
                .andRespond(withSuccess("{\"code\":0}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith("https://open.feishu.cn/open-apis/im/v1/messages")))
                .andRespond(withSuccess("{\"code\":0}", MediaType.APPLICATION_JSON));
        client.sendCard("oc_x", "t", "red", List.of("a"));
        client.sendCard("oc_x", "t2", "red", List.of("b"));
        server.verify(); // 恰好 3 次请求：1 token + 2 消息
    }

    @Test
    @DisplayName("给定发送返回非零code，when发送，then返回false")
    void given非零code_when发送_thenFalse() {
        expectToken();
        server.expect(requestTo(startsWith("https://open.feishu.cn/open-apis/im/v1/messages")))
                .andRespond(withSuccess("{\"code\":230001,\"msg\":\"no permission\"}", MediaType.APPLICATION_JSON));
        assertThat(client.sendCard("oc_x", "t", "red", List.of("a"))).isFalse();
    }

    @Test
    @DisplayName("给定token失效码，when发送，then刷新token重试一次成功")
    void giventoken失效码_when发送_then刷新重试成功() {
        expectToken(); // 第一次 token
        server.expect(requestTo(startsWith("https://open.feishu.cn/open-apis/im/v1/messages")))
                .andRespond(withSuccess("{\"code\":99991663,\"msg\":\"token invalid\"}", MediaType.APPLICATION_JSON));
        expectToken(); // 失效后重新获取
        server.expect(requestTo(startsWith("https://open.feishu.cn/open-apis/im/v1/messages")))
                .andRespond(withSuccess("{\"code\":0}", MediaType.APPLICATION_JSON));
        assertThat(client.sendCard("oc_x", "t", "red", List.of("a"))).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("给定配置缺失，when发送，then直接false且零网络请求")
    void given配置缺失_when发送_then零请求() {
        props.getIm().setAppId("");
        assertThat(client.sendCard("oc_x", "t", "red", List.of("a"))).isFalse();
        server.verify(); // 0 expectations → 0 requests
    }

    @Test
    @DisplayName("给定编程式null凭证，when发送，then返回false且零网络请求")
    void given编程式null凭证_when发送_thenFalse且零请求() {
        props.getIm().setAppId(null);
        assertThat(client.sendCard("oc_x", "t", "red", List.of("a"))).isFalse();
        server.verify(); // 0 expectations → 0 requests
    }
}
