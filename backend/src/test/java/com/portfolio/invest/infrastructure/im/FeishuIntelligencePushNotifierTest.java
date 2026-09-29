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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 情报推送飞书实现（D10/D11）：真实 FeishuClient + MockRestServiceServer 走通 wire 契约——
 * 群推 receive_id_type=chat_id/chatId；open_id 单发 receive_id_type=open_id 且门禁仅
 * appId/appSecret（不要求群 chatId 配置）；未配置零请求 false；防御兜底异常吞掉不抛。
 */
class FeishuIntelligencePushNotifierTest {

    private static final String TOKEN_OK =
            "{\"code\":0,\"msg\":\"ok\",\"tenant_access_token\":\"t-abc\",\"expire\":7200}";

    private InvestProperties props;
    private MockRestServiceServer server;
    private FeishuIntelligencePushNotifier notifier;

    @BeforeEach
    void setUp() {
        props = new InvestProperties();
        props.getIm().setAppId("cli_intel");
        props.getIm().setAppSecret("secret_intel");
        props.getIm().setChatId("oc_intel");
        RestClient.Builder builder = RestClient.builder().baseUrl("https://open.feishu.cn");
        server = MockRestServiceServer.bindTo(builder).build();
        notifier = new FeishuIntelligencePushNotifier(new FeishuClient(props, builder.build()), props);
    }

    private void expectToken() {
        server.expect(requestTo(startsWith("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(TOKEN_OK, MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("给定已配置群，when群推，thenPOST chat_id 端点且卡片带标题与正文")
    void givenConfiguredChat_whenSendToGroup_thenChatIdEndpoint() {
        expectToken();
        server.expect(requestTo(startsWith(
                        "https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=chat_id")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer t-abc"))
                .andExpect(jsonPath("$.receive_id").value("oc_intel"))
                .andExpect(jsonPath("$.msg_type").value("interactive"))
                .andExpect(jsonPath("$.content", containsString("【盘前简报】2026-09-29")))
                .andExpect(jsonPath("$.content", containsString("宏观与政策")))
                .andRespond(withSuccess("{\"code\":0}", MediaType.APPLICATION_JSON));

        boolean ok = notifier.sendToGroup("【盘前简报】2026-09-29", "blue",
                List.of("## 宏观与政策", "- 条目一"));

        assertThat(ok).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("给定未配置群chatId，when openId单发，thenPOST open_id 端点且不要求chatId")
    void givenNoChatIdConfigured_whenSendToUser_thenOpenIdEndpoint() {
        props.getIm().setChatId(""); // 单发门禁仅 appId/appSecret（D10）
        expectToken();
        server.expect(requestTo(startsWith(
                        "https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=open_id")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.receive_id").value("ou_user"))
                .andExpect(jsonPath("$.msg_type").value("interactive"))
                .andRespond(withSuccess("{\"code\":0}", MediaType.APPLICATION_JSON));

        boolean ok = notifier.sendToUser("ou_user", "【情报提醒】", "blue", List.of("行一"));

        assertThat(ok).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("给定未配置appId，when推送，then群推单发均false且零网络请求")
    void givenUnconfigured_whenPush_thenFalseWithoutRequests() {
        props.getIm().setAppId("");

        assertThat(notifier.sendToGroup("t", "blue", List.of("a"))).isFalse();
        assertThat(notifier.sendToUser("ou_user", "t", "blue", List.of("a"))).isFalse();
        server.verify(); // 0 expectations → 0 requests
    }

    @Test
    @DisplayName("给定空openId，when单发，thenfalse且零网络请求")
    void givenBlankOpenId_whenSendToUser_thenFalseWithoutRequests() {
        assertThat(notifier.sendToUser(" ", "t", "blue", List.of("a"))).isFalse();
        server.verify();
    }

    @Test
    @DisplayName("给定客户端抛异常，when群推与单发，then防御兜底吞掉返回false不抛")
    void givenClientThrows_whenPush_thenSwallowedFalse() {
        FeishuClient client = mock(FeishuClient.class);
        when(client.sendCard(any(), any(), any(), anyList()))
                .thenThrow(new RuntimeException("boom"));
        when(client.sendCardByOpenId(any(), any(), any(), anyList()))
                .thenThrow(new RuntimeException("boom"));
        FeishuIntelligencePushNotifier defensive = new FeishuIntelligencePushNotifier(client, props);

        assertThatCode(() -> {
            assertThat(defensive.sendToGroup("t", "blue", List.of("a"))).isFalse();
            assertThat(defensive.sendToUser("ou_x", "t", "blue", List.of("a"))).isFalse();
        }).doesNotThrowAnyException();
    }
}
