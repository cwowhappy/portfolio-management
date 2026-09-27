package com.portfolio.invest.infrastructure.im;

import com.portfolio.invest.application.im.ImInboundMessage;
import com.portfolio.invest.application.im.ImMessageListener;
import com.portfolio.invest.config.InvestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class FeishuWsClientTest {

    private final ImMessageListener listener = mock(ImMessageListener.class);
    private InvestProperties props;
    private FeishuWsClient client;

    @BeforeEach
    void setUp() {
        props = new InvestProperties();
        props.getIm().setAppId("cli_x");
        props.getIm().setAppSecret("s");
        props.getIm().setDialogueEnabled(true);
        client = new FeishuWsClient(props, listener, Runnable::run); // 直执行执行器
    }

    @Test
    @DisplayName("给定文本消息，when分发，then解析出text并转投listener")
    void given文本消息_when分发_then转投listener() {
        client.dispatch("oc_1", "om_1", "ou_owner", "p2p", "text", "{\"text\":\"我的持仓怎么样\"}");
        ArgumentCaptor<ImInboundMessage> cap = ArgumentCaptor.forClass(ImInboundMessage.class);
        verify(listener).onMessage(cap.capture());
        assertThat(cap.getValue().text()).isEqualTo("我的持仓怎么样");
        assertThat(cap.getValue().openId()).isEqualTo("ou_owner");
    }

    @Test
    @DisplayName("给定同一message_id重复分发，when第二次，then被去重吞掉")
    void given重复消息_when再次分发_then去重() {
        client.dispatch("oc_1", "om_dup", "ou_owner", "p2p", "text", "{\"text\":\"a\"}");
        client.dispatch("oc_1", "om_dup", "ou_owner", "p2p", "text", "{\"text\":\"a\"}");
        verify(listener, org.mockito.Mockito.times(1)).onMessage(any());
    }

    @Test
    @DisplayName("给定content非JSON或无text键，when分发，then转投但text为null")
    void given坏content_when分发_thenText为空() {
        client.dispatch("oc_1", "om_2", "ou_owner", "p2p", "text", "not-json");
        ArgumentCaptor<ImInboundMessage> cap = ArgumentCaptor.forClass(ImInboundMessage.class);
        verify(listener).onMessage(cap.capture());
        assertThat(cap.getValue().text()).isNull();
    }

    @Test
    @DisplayName("给定listener抛异常，when分发，then异常被吞不外溢")
    void givenlistener异常_when分发_then吞掉() {
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(listener).onMessage(any());
        client.dispatch("oc_1", "om_3", "ou_owner", "p2p", "text", "{\"text\":\"a\"}");
        // 无异常抛出即通过；重复消息仍应被去重记录（上一条已登记）
        client.dispatch("oc_1", "om_3", "ou_owner", "p2p", "text", "{\"text\":\"a\"}");
        verify(listener, org.mockito.Mockito.times(1)).onMessage(any());
    }

    @Test
    @DisplayName("给定未启用对话或未配置凭证，whenStart，then不建连")
    void given未启用_whenStart_then不建连() {
        props.getIm().setDialogueEnabled(false);
        FeishuWsClient off = new FeishuWsClient(props, listener, Runnable::run);
        off.start(); // 应静默跳过，无异常
        assertThat(off.isRunning()).isFalse();
    }
}
