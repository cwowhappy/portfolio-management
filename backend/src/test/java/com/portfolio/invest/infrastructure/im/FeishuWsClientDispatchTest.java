package com.portfolio.invest.infrastructure.im;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.invest.application.im.ImCommandRouter;
import com.portfolio.invest.application.im.ImInboundMessage;
import com.portfolio.invest.application.im.ImMessageListener;
import com.portfolio.invest.config.InvestProperties;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * ws 分发命令前置路由单测（P4 Task 5）：命令命中 → feishuClient.sendReply 回话术且
 * 不进对话桥；未命中/无路由 bean → 照旧透传 listener；路由抛异常 → 吞掉不外溢也不
 * 进对话桥（命令类消息不落到 LLM）。照 FeishuWsClientTest 形态：直执行执行器 + mock。
 */
class FeishuWsClientDispatchTest {

    private final ImMessageListener listener = mock(ImMessageListener.class);
    private final ImCommandRouter router = mock(ImCommandRouter.class);
    private final FeishuClient feishuClient = mock(FeishuClient.class);
    private FeishuWsClient client;

    @BeforeEach
    void setUp() {
        InvestProperties props = new InvestProperties();
        props.getIm().setAppId("cli_x");
        props.getIm().setAppSecret("s");
        props.getIm().setDialogueEnabled(true);
        client = new FeishuWsClient(props, listener, router, feishuClient, Runnable::run);
    }

    @Test
    @DisplayName("给定命令命中路由，when分发，then sendReply 回话术且不进 listener")
    void givenRoutedCommand_whenDispatch_thenRepliesAndSkipsListener() {
        when(router.tryRoute(any())).thenReturn(Optional.of("绑定成功：将为你推送个性化盘前简报与重大公告提醒"));

        client.dispatch("oc_1", "om_cmd", "ou_owner", "p2p", "text", "{\"text\":\"483920\"}");

        verify(feishuClient).sendReply("om_cmd", "绑定成功：将为你推送个性化盘前简报与重大公告提醒");
        verify(listener, never()).onMessage(any());
    }

    @Test
    @DisplayName("给定路由未命中，when分发，then照旧透传 listener（不回复）")
    void givenUnroutedText_whenDispatch_thenFallsThroughToListener() {
        when(router.tryRoute(any())).thenReturn(Optional.empty());

        client.dispatch("oc_1", "om_chat", "ou_owner", "p2p", "text", "{\"text\":\"我的持仓怎么样\"}");

        ArgumentCaptor<ImInboundMessage> cap = ArgumentCaptor.forClass(ImInboundMessage.class);
        verify(listener).onMessage(cap.capture());
        assertThat(cap.getValue().text()).isEqualTo("我的持仓怎么样");
        verify(feishuClient, never()).sendReply(anyString(), anyString());
    }

    @Test
    @DisplayName("给定无命令路由（生产无 bean / 既有构造器），when分发，then照旧透传 listener")
    void givenNoRouter_whenDispatch_thenPassthroughAsBefore() {
        InvestProperties props = new InvestProperties();
        props.getIm().setAppId("cli_x");
        props.getIm().setAppSecret("s");
        props.getIm().setDialogueEnabled(true);
        FeishuWsClient noRouter = new FeishuWsClient(props, listener, Runnable::run);

        noRouter.dispatch("oc_1", "om_plain", "ou_owner", "p2p", "text", "{\"text\":\"483920\"}");

        // 无路由：即便文本是 6 位码也照旧进对话桥（透传语义零回归）
        ArgumentCaptor<ImInboundMessage> cap = ArgumentCaptor.forClass(ImInboundMessage.class);
        verify(listener).onMessage(cap.capture());
        assertThat(cap.getValue().text()).isEqualTo("483920");
    }

    @Test
    @DisplayName("给定路由抛异常，when分发，then吞掉不外溢且不进对话桥")
    void givenRouterThrows_whenDispatch_thenSwallowedWithoutListener() {
        org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
                .when(router).tryRoute(any());

        client.dispatch("oc_1", "om_err", "ou_owner", "p2p", "text", "{\"text\":\"483920\"}");

        verify(listener, never()).onMessage(any());
        verify(feishuClient, never()).sendReply(anyString(), anyString());
    }
}
