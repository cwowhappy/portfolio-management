package com.portfolio.invest.infrastructure.im;

import com.portfolio.invest.application.im.ImInboundMessage;
import com.portfolio.invest.application.im.ImMessageListener;
import com.portfolio.invest.config.InvestProperties;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    // ── B10：入站有界队列满丢弃留痕 ──────────────────────────────

    @Test
    @DisplayName("给定小容量队列已满，when再分发，then不抛不阻塞、丢弃计数+1、被丢消息不进listener")
    void given队列已满_when分发_then丢弃留痕且不进listener() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch processedTwo = new CountDownLatch(2);
        List<String> received = Collections.synchronizedList(new ArrayList<>());
        doAnswer(inv -> {
            ImInboundMessage m = inv.getArgument(0);
            received.add(m.messageId());
            processedTwo.countDown();
            started.countDown();
            release.await(); // 卡住 worker：制造队列积压
            return null;
        }).when(listener).onMessage(any());
        FeishuWsClient bounded = new FeishuWsClient(props, listener, 1); // 容量 1

        try {
            bounded.dispatch("oc_1", "om_a", "ou_owner", "p2p", "text", "{\"text\":\"a\"}");
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue(); // worker 已忙，队列空
            bounded.dispatch("oc_1", "om_b", "ou_owner", "p2p", "text", "{\"text\":\"b\"}"); // 未满：正常入队
            bounded.dispatch("oc_1", "om_c", "ou_owner", "p2p", "text", "{\"text\":\"c\"}"); // 满：丢弃留痕
            assertThat(bounded.droppedCount()).isEqualTo(1);
        } finally {
            release.countDown();
        }
        assertThat(processedTwo.await(5, TimeUnit.SECONDS)).isTrue(); // om_a/om_b 均已进入处理
        assertThat(received).containsExactly("om_a", "om_b"); // 被丢弃的 om_c 未进 listener
    }

    // ── B10：非 owner 纯对话消息入队前丢弃（owner 未配置全放行） ──

    @Test
    @DisplayName("给定已配置owner，when非owner纯对话消息分发，then入队前丢弃不进listener")
    void given配置owner_when非owner对话消息_then入队前丢弃() {
        props.getIm().setOwnerOpenId("ou_owner");
        client.dispatch("oc_1", "om_gate1", "ou_stranger", "p2p", "text", "{\"text\":\"我的持仓怎么样\"}");
        verify(listener, never()).onMessage(any());
    }

    @Test
    @DisplayName("给定已配置owner，when owner消息分发，then照旧透传listener")
    void given配置owner_when_owner消息_then照旧透传() {
        props.getIm().setOwnerOpenId("ou_owner");
        client.dispatch("oc_1", "om_gate2", "ou_owner", "p2p", "text", "{\"text\":\"我的持仓怎么样\"}");
        verify(listener).onMessage(any(ImInboundMessage.class));
    }

    @Test
    @DisplayName("给定未配置owner（默认空），when任意openId消息分发，then全放行（向后兼容）")
    void given未配置owner_when任意消息_then全放行() {
        client.dispatch("oc_1", "om_gate3", "ou_stranger", "p2p", "text", "{\"text\":\"随便聊聊\"}");
        verify(listener).onMessage(any(ImInboundMessage.class));
    }
}
