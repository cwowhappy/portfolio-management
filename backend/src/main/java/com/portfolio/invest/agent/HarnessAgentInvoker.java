package com.portfolio.invest.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 默认 invoker：复用 AG-UI 同源装配（HarnessAgentFactory），单实例缓存（单用户对话天然串行）。 */
@Component
public class HarnessAgentInvoker implements FeishuAgentInvoker {

    private static final Logger log = LoggerFactory.getLogger(HarnessAgentInvoker.class);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(240);

    private final ObjectProvider<HarnessAgentFactory> factoryProvider;
    private volatile HarnessAgent cached;
    private volatile Long cachedForUserId;

    public HarnessAgentInvoker(ObjectProvider<HarnessAgentFactory> factoryProvider) {
        this.factoryProvider = factoryProvider;
    }

    @Override
    public String ask(Long userId, String sessionId, String text) {
        HarnessAgentFactory factory = factoryProvider.getIfAvailable();
        if (factory == null) {
            throw new IllegalStateException("DEEPSEEK_API_KEY 未配置，agent 不可用");
        }
        HarnessAgent agent = agentFor(factory, userId);
        Msg reply = agent.call(
                        Msg.builder().role(MsgRole.USER).textContent(text).build(),
                        RuntimeContext.builder().userId(userId.toString()).sessionId(sessionId).build())
                .block(CALL_TIMEOUT);
        String content = reply == null ? null : reply.getTextContent();
        return content == null || content.isBlank() ? "（模型未返回内容，请重试）" : content;
    }

    private HarnessAgent agentFor(HarnessAgentFactory factory, Long userId) {
        HarnessAgent agent = cached;
        if (agent == null || !userId.equals(cachedForUserId)) {
            agent = factory.build(userId);
            cached = agent;
            cachedForUserId = userId;
        }
        return agent;
    }
}
