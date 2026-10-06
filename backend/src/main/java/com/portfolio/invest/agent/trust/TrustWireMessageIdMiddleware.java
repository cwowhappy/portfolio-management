package com.portfolio.invest.agent.trust;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * AG-UI 线上 messageId 观察中间件（MS-29 B5）：agentscope 2.0.3 的 TEXT_MESSAGE messageId
 * 取模型调用级的 replyId（{@code modelCallStream} 内 {@code UUID.randomUUID()} 去横线生成，
 * javap/集成实测），<strong>不是</strong> assistant {@code Msg.getId()}（= ChatResponse id）——
 * Custom 事件要被前端按消息 id 映射（设计规格 §4.3「AG-UI 线上 messageId 同源」），必须携带
 * 线上 id。本中间件在 {@code onModelCall}（call() 与 streamEvents 两路共用的模型调用事件链，
 * CallExecution 内建）观察 {@link TextBlockStartEvent#getReplyId()}，把<strong>最近一次</strong>
 * 文本块的 replyId 写入 RuntimeContext，供 {@link TrustAgentHook} 在回合收尾发事件时取用。
 *
 * <p>只观察不改流（doOnNext 旁路）；无弃用标记（MiddlewareBase 非 deprecated）——与 Hook
 * 的分工：Hook 承担 Msg 改写（唯一能力口，取舍见 TrustAgentHook javadoc），本类承担流观察。
 */
public final class TrustWireMessageIdMiddleware implements MiddlewareBase {

    /** RuntimeContext 字符串键：最近一次文本块的线上 messageId（replyId）。 */
    public static final String RC_KEY = "trust.wireMessageId";

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext rc, ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        Flux<AgentEvent> events = next.apply(input);
        if (rc == null) {
            return events;
        }
        return events.doOnNext(event -> {
            if (event instanceof TextBlockStartEvent start && start.getReplyId() != null) {
                rc.put(RC_KEY, start.getReplyId());
            }
        });
    }
}
