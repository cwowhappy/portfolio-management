package com.portfolio.invest.agent.trust;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.middleware.ModelCallInput;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * TrustWireMessageIdMiddleware 单元测试（MS-29 B5）：onModelCall 旁路观察文本块 replyId
 * 写入 RuntimeContext（最近一次覆盖），不改流、不吞事件、rc 缺席安全。
 */
class TrustWireMessageIdMiddlewareTest {

    private final TrustWireMessageIdMiddleware middleware = new TrustWireMessageIdMiddleware();

    @DisplayName("文本块开始事件：replyId 写入 rc，最近一次覆盖前者")
    @Test
    void givenTextBlockStarts_whenOnModelCall_thenLatestReplyIdRecorded() {
        RuntimeContext rc = RuntimeContext.builder().userId("1").sessionId("s").build();
        AtomicInteger downstream = new AtomicInteger();

        Flux<AgentEvent> out = middleware.onModelCall(null, rc,
                new ModelCallInput(List.of(), null, null, null),
                input -> Flux.just(
                        new TextBlockStartEvent("reply-1", "b1"),
                        new TextBlockEndEvent("reply-1", "b1"),
                        new TextBlockStartEvent("reply-2", "b2"))
                        .doOnNext(e -> downstream.incrementAndGet()));

        assertThat(out.collectList().block()).hasSize(3);
        assertThat(downstream.get()).isEqualTo(3); // 流不改不吞
        assertThat((String) rc.get(TrustWireMessageIdMiddleware.RC_KEY)).isEqualTo("reply-2");
    }

    @DisplayName("非文本块事件与 rc 缺席：安全旁路")
    @Test
    void givenNoTextStartOrNoRc_whenOnModelCall_thenPassthrough() {
        Flux<AgentEvent> noRc = middleware.onModelCall(null, null,
                new ModelCallInput(List.of(), null, null, null),
                input -> Flux.just(new TextBlockStartEvent("reply-x", "b1")));
        assertThat(noRc.collectList().block()).hasSize(1);

        RuntimeContext rc = RuntimeContext.builder().userId("1").sessionId("s").build();
        Flux<AgentEvent> noStart = middleware.onModelCall(null, rc,
                new ModelCallInput(List.of(), null, null, null),
                input -> Flux.just(new TextBlockEndEvent("reply-y", "b1")));
        assertThat(noStart.collectList().block()).hasSize(1);
        assertThat((String) rc.get(TrustWireMessageIdMiddleware.RC_KEY)).isNull();
    }
}
