package com.portfolio.invest.agent;

import com.portfolio.invest.application.im.ImInboundMessage;
import com.portfolio.invest.application.im.ImReplyPort;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeishuDialogueBridgeTest {

    private final FeishuAgentInvoker invoker = mock(FeishuAgentInvoker.class);
    private final ImReplyPort replyPort = mock(ImReplyPort.class);
    private final UserRepository userRepo = mock(UserRepository.class);
    private InvestProperties props;
    private FeishuDialogueBridge bridge;

    @BeforeEach
    void setUp() {
        props = new InvestProperties();
        props.getIm().setAppId("cli_x");
        props.getIm().setAppSecret("s");
        props.getIm().setOwnerUsername("admin");
        props.getIm().setOwnerOpenId("ou_owner");
        props.getIm().setDialogueEnabled(true);
        bridge = new FeishuDialogueBridge(invoker, replyPort, userRepo, props);
        User owner = mock(User.class);
        when(owner.id()).thenReturn(7L);
        when(userRepo.findByUsername("admin")).thenReturn(Optional.of(owner));
    }

    private static ImInboundMessage msg(String openId, String msgType, String text) {
        return new ImInboundMessage("oc_1", "om_" + System.nanoTime(), openId, "p2p", msgType, text);
    }

    @Test
    @DisplayName("给定owner文本消息，when处理，then以feishu会话键调agent并回复其文本")
    void givenOwner文本_when处理_then调agent并回复() {
        when(invoker.ask(any(), anyString(), anyString())).thenReturn("当前持仓一切正常");
        bridge.onMessage(msg("ou_owner", "text", "我的持仓怎么样"));
        ArgumentCaptor<String> sessionId = ArgumentCaptor.forClass(String.class);
        verify(invoker).ask(any(), sessionId.capture(), anyString());
        assertThat(sessionId.getValue()).startsWith("feishu-");
        verify(replyPort).reply(anyString(), org.mockito.ArgumentMatchers.eq("当前持仓一切正常"));
    }

    @Test
    @DisplayName("给定白名单未配置，when任意消息，then不回复且不查用户")
    void given白名单空_when消息_then仅日志提示() {
        props.getIm().setOwnerOpenId("");
        bridge.onMessage(msg("ou_someone", "text", "你好"));
        verify(userRepo, never()).findByUsername(anyString());
        verify(replyPort, never()).reply(anyString(), anyString());
    }

    @Test
    @DisplayName("给定非owner消息，when处理，then静默忽略")
    void given非owner_when消息_then忽略() {
        bridge.onMessage(msg("ou_stranger", "text", "你好"));
        verify(invoker, never()).ask(any(), anyString(), anyString());
        verify(replyPort, never()).reply(anyString(), anyString());
    }

    @Test
    @DisplayName("给定非文本消息，when处理，then回复仅支持文本的提示")
    void given非文本_when消息_then回复提示() {
        bridge.onMessage(msg("ou_owner", "image", null));
        verify(replyPort).reply(anyString(), org.mockito.ArgumentMatchers.contains("文本"));
    }

    @Test
    @DisplayName("给定text为null（解析失败），when处理，then回复提示")
    void given文本解析失败_when消息_then回复提示() {
        bridge.onMessage(msg("ou_owner", "text", null));
        verify(replyPort).reply(anyString(), org.mockito.ArgumentMatchers.contains("文本"));
    }

    @Test
    @DisplayName("给定agent抛异常，when处理，then回复失败提示且不外溢")
    void givenagent异常_when消息_then回复失败提示() {
        when(invoker.ask(any(), anyString(), anyString())).thenThrow(new IllegalStateException("llm down"));
        assertThatCode(() -> bridge.onMessage(msg("ou_owner", "text", "你好"))).doesNotThrowAnyException();
        verify(replyPort).reply(anyString(), org.mockito.ArgumentMatchers.contains("失败"));
    }

    @Test
    @DisplayName("给定超长回复，when处理，then截断到3000字符并带省略提示")
    void given超长回复_when处理_then截断() {
        when(invoker.ask(any(), anyString(), anyString())).thenReturn("长".repeat(4000));
        bridge.onMessage(msg("ou_owner", "text", "你好"));
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(replyPort).reply(anyString(), cap.capture());
        assertThat(cap.getValue().length()).isLessThanOrEqualTo(3010);
        assertThat(cap.getValue()).endsWith("…（已截断）");
    }

    @Test
    @DisplayName("给定owner用户不存在，when处理，then不调agent不回复")
    void givenowner不存在_when消息_then跳过() {
        when(userRepo.findByUsername("admin")).thenReturn(Optional.empty());
        bridge.onMessage(msg("ou_owner", "text", "你好"));
        verify(invoker, never()).ask(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("给定群聊消息，when处理，then忽略")
    void given群聊_when消息_then忽略() {
        ImInboundMessage group = new ImInboundMessage("oc_g", "om_g", "ou_owner", "group", "text", "你好");
        bridge.onMessage(group);
        verify(invoker, never()).ask(any(), anyString(), anyString());
    }
}
