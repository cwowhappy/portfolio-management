package com.portfolio.invest.agent;

import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HarnessAgentInvokerTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<HarnessAgentFactory> provider = mock(ObjectProvider.class);
    private final HarnessAgentFactory factory = mock(HarnessAgentFactory.class);
    private final HarnessAgent agent = mock(HarnessAgent.class);

    @Test
    @DisplayName("给定agent返回文本Msg，when询问，then返回其文本内容")
    void givenAgent回复_when询问_then返回文本() {
        when(provider.getIfAvailable()).thenReturn(factory);
        when(factory.build(7L)).thenReturn(agent);
        Msg reply = mock(Msg.class);
        when(reply.getTextContent()).thenReturn("一切正常");
        when(agent.call(org.mockito.ArgumentMatchers.any(Msg.class),
                org.mockito.ArgumentMatchers.any(io.agentscope.core.agent.RuntimeContext.class)))
                .thenReturn(Mono.just(reply));
        HarnessAgentInvoker invoker = new HarnessAgentInvoker(provider);
        assertThat(invoker.ask(7L, "feishu-ou_x", "你好")).isEqualTo("一切正常");
    }

    @Test
    @DisplayName("给定工厂bean不存在（无DEEPSEEK_KEY），when询问，then抛出可诊断异常")
    void given无工厂_when询问_then抛异常() {
        when(provider.getIfAvailable()).thenReturn(null);
        HarnessAgentInvoker invoker = new HarnessAgentInvoker(provider);
        assertThatThrownBy(() -> invoker.ask(7L, "feishu-ou_x", "你好"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEEPSEEK_API_KEY");
    }
}
