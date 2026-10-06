package com.portfolio.invest.agent.trust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.core.agent.RuntimeContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 真值池上下文双通道（MS-29 B3）：取用顺序 RuntimeContext 优先、ThreadLocal 兜底；
 * 两通道皆空则新建并双挂（回合内工具调用共享同一池）；reset 清理回退通道防池化线程跨回合串值。
 */
class TrustContextTest {

    @AfterEach
    void cleanFallback() {
        TrustContext.reset();
    }

    private static ToolInvocation invocation(String toolName) {
        return new ToolInvocation(toolName, Map.of(), "{}", List.of(), "09:30:00",
                ToolInvocation.AsOfKind.DATA, false, false);
    }

    @DisplayName("RuntimeContext 通道：同 rc 重复取用为同一实例（回合内累积）")
    @Test
    void givenSameRuntimeContext_whenCurrent_thenSameInstanceAccumulates() {
        RuntimeContext rc = RuntimeContext.empty();

        TrustContext first = TrustContext.current(rc);
        first.record(invocation("get_quote"));
        TrustContext second = TrustContext.current(rc);
        second.record(invocation("get_kline"));

        assertThat(second).isSameAs(first);
        assertThat(first.invocations()).hasSize(2);
    }

    @DisplayName("ThreadLocal 兜底通道：rc 缺失时同线程共享同一池")
    @Test
    void givenNullRuntimeContext_whenCurrent_thenThreadLocalFallback() {
        TrustContext first = TrustContext.current(null);
        first.record(invocation("get_quote"));
        TrustContext second = TrustContext.current(null);

        assertThat(second).isSameAs(first);
        assertThat(second.invocations()).hasSize(1);
    }

    @DisplayName("取用顺序：RuntimeContext 已挂实例优先于 ThreadLocal 残留值")
    @Test
    void givenBothChannelsPopulated_whenCurrent_thenRuntimeContextWins() {
        TrustContext stale = TrustContext.current(null);   // 只落 ThreadLocal
        RuntimeContext rc = RuntimeContext.empty();
        TrustContext attached = TrustContext.current(rc);  // rc 未挂 → 新建并双挂（覆盖残留）

        assertThat(attached).isNotSameAs(stale);
        attached.record(invocation("get_quote"));

        assertThat(TrustContext.current(rc)).isSameAs(attached);
        assertThat(stale.invocations()).as("残留池不被写入").isEmpty();
    }

    @DisplayName("reset 清理 ThreadLocal 回退通道（回合收尾，CurrentUserHolder 先例）")
    @Test
    void givenFallbackPopulated_whenReset_thenCleared() {
        TrustContext before = TrustContext.current(null);
        before.record(invocation("get_quote"));

        TrustContext.reset();

        assertThat(TrustContext.current(null)).isNotSameAs(before);
        assertThat(TrustContext.current(null).invocations()).isEmpty();
    }

    @DisplayName("invocations() 返回只读快照：外部改动不影响池内真值")
    @Test
    void givenRecordedInvocations_whenSnapshotMutated_thenPoolUntouched() {
        RuntimeContext rc = RuntimeContext.empty();
        TrustContext context = TrustContext.current(rc);
        context.record(invocation("get_quote"));

        List<ToolInvocation> snapshot = context.invocations();
        assertThatThrownBy(snapshot::clear)
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(context.invocations()).hasSize(1);
    }
}
