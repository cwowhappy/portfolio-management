package com.portfolio.invest.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * rc 桥接中间件单元测试（agentscope 2.0.4 升级）：ctx 依序送抵全部消费者后放行 next；
 * 链序=注册序（2.0.4 MiddlewareChain 无 order 排序），桥接以先注册保证最外层；
 * null ctx 照送（Hook 侧自防 null，与 2.0.3 setRuntimeContext 缺席语义一致）。
 * agent 参数不解引用（传 null），事件流原样透传。
 */
class RuntimeContextBridgeMiddlewareTest {

    @Test
    @DisplayName("onAgent 把 ctx 依序送抵全部消费者后放行 next，事件流原样透传")
    void givenCtxAndConsumers_whenOnAgent_thenDeliversCtxThenDelegates() {
        List<String> seq = new ArrayList<>();
        List<RuntimeContext> seen = new ArrayList<>();
        RuntimeContext ctx = RuntimeContext.builder().userId("42").sessionId("s-1").build();
        RuntimeContextBridgeMiddleware mw = new RuntimeContextBridgeMiddleware(
                rc -> { seen.add(rc); seq.add("c1"); },
                rc -> { seen.add(rc); seq.add("c2"); });
        AgentEndEvent end = new AgentEndEvent("r-1");

        Flux<AgentEvent> out = mw.onAgent(null, ctx, new AgentInput(List.of()),
                input -> { seq.add("next"); return Flux.just(end); });

        assertThat(out.collectList().block()).containsExactly(end);
        assertThat(seen).containsExactly(ctx, ctx);
        assertThat(seq).containsExactly("c1", "c2", "next");
    }

    @Test
    @DisplayName("order() 恒为 Integer.MAX_VALUE——语义声明（2.0.4 链序=注册序，非排序依据）")
    void givenBridgeMiddleware_whenOrder_thenOutermostMaxValue() {
        assertThat(new RuntimeContextBridgeMiddleware(rc -> { }).order())
                .isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("null ctx 照送不拦截（裸调用路径，Hook 侧自防 null）")
    void givenNullCtx_whenOnAgent_thenStillDelivered() {
        List<RuntimeContext> seen = new ArrayList<>();
        RuntimeContextBridgeMiddleware mw = new RuntimeContextBridgeMiddleware(seen::add);
        mw.onAgent(null, null, new AgentInput(List.of()), input -> Flux.empty())
                .collectList().block();
        assertThat(seen).singleElement().isNull();
    }

    @Test
    @DisplayName("consumerCount 暴露消费者数（装配断言口）")
    void givenTwoConsumers_whenConsumerCount_thenReflectsConsumerCount() {
        assertThat(new RuntimeContextBridgeMiddleware(rc -> { }, rc -> { }).consumerCount())
                .isEqualTo(2);
    }
}
