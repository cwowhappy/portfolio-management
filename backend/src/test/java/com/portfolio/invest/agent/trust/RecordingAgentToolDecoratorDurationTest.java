package com.portfolio.invest.agent.trust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * 工具调用计时（MS-30 B6，设计规格 §5.1）：decorator 在 Mono.defer 前取 nanoTime 起点，
 * doOnSuccess/doOnError 折毫秒随 ToolInvocation 第 9 分量 durationMs 入池——成功/失败两路
 * 都须计时（失败时延同样是观测信号）。真墙钟计时（固定 Clock 只管 callTime 文案），
 * 假 delegate 睡 ≥50ms 后断言 durationMs >= 50。
 */
class RecordingAgentToolDecoratorDurationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-05T01:30:00Z"), ZoneId.of("Asia/Shanghai"));
    /** 睡眠下限与断言阈值分离：留 10ms 余量抗毫秒截断与调度抖动。 */
    private static final long SLEEP_MS = 60;
    private static final long ASSERT_AT_LEAST_MS = 50;

    @AfterEach
    void cleanThreadLocalFallback() {
        TrustContext.reset();
    }

    /** 可编程 stub：behavior 决定 callAsync 行为；sleep 模拟慢工具（网络/下游行情源）。 */
    static class StubTool extends ToolBase {
        private final Function<ToolCallParam, Mono<ToolResultBlock>> behavior;

        StubTool(String name, Function<ToolCallParam, Mono<ToolResultBlock>> behavior) {
            super(ToolBase.builder()
                    .name(name)
                    .description("计时验证用 stub 工具")
                    .inputSchema(Map.of("type", "object"))
                    .readOnly(true)
                    .concurrencySafe(true));
            this.behavior = behavior;
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return behavior.apply(param);
        }
    }

    private static ToolUseBlock use(String id, String name) {
        return new ToolUseBlock(id, name, Map.of(), "{}", Map.of());
    }

    private static ToolCallParam param(ToolUseBlock use, RuntimeContext rc) {
        return ToolCallParam.builder().toolUseBlock(use).input(use.getInput()).runtimeContext(rc).build();
    }

    /** 订阅线程上睡 sleepMs 再返回（Mono 冷流——block() 触发即真耗时）。 */
    private static ToolResultBlock sleepThen(long sleepMs, String text) {
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("计时测试被中断", e);
        }
        return ToolResultBlock.text(text);
    }

    @DisplayName("慢工具成功：durationMs 计入真值池，至少覆盖 delegate 睡眠时长")
    @Test
    void givenSlowDelegate_whenCallAsync_thenDurationMsAtLeastSleep() {
        RuntimeContext rc = RuntimeContext.empty();
        RecordingAgentToolDecorator decorator = new RecordingAgentToolDecorator(
                new StubTool("slow_tool", p -> Mono.just(sleepThen(SLEEP_MS, "{\"time\":\"09:30:15\"}"))),
                false, MAPPER, CLOCK);

        decorator.callAsync(param(use("call_d1", "slow_tool"), rc)).block();

        ToolInvocation invocation = TrustContext.current(rc).invocations().get(0);
        assertThat(invocation.durationMs())
                .as("计时含 Mono 装配与订阅调度，须 >= delegate 睡眠时长")
                .isGreaterThanOrEqualTo(ASSERT_AT_LEAST_MS);
        assertThat(invocation.failed()).isFalse();
    }

    @DisplayName("慢工具失败：doOnError 路径同样计时，failed=true 且异常语义不变")
    @Test
    void givenSlowFailingDelegate_whenCallAsync_thenFailureDurationRecorded() {
        RuntimeContext rc = RuntimeContext.empty();
        RecordingAgentToolDecorator decorator = new RecordingAgentToolDecorator(
                new StubTool("slow_fail", p -> {
                    sleepThen(SLEEP_MS, "");
                    return Mono.error(new IllegalStateException("行情源超时"));
                }),
                false, MAPPER, CLOCK);

        assertThatThrownBy(() ->
                decorator.callAsync(param(use("call_d2", "slow_fail"), rc)).block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("行情源超时");

        ToolInvocation invocation = TrustContext.current(rc).invocations().get(0);
        assertThat(invocation.failed()).isTrue();
        assertThat(invocation.durationMs())
                .as("失败路径的时延同样是观测信号（§5.1 doOnError 带 elapsed）")
                .isGreaterThanOrEqualTo(ASSERT_AT_LEAST_MS);
    }

    @DisplayName("即时返回的快工具：durationMs 非负且不抛（0 毫秒是合法值）")
    @Test
    void givenImmediateDelegate_whenCallAsync_thenDurationNonNegative() {
        RuntimeContext rc = RuntimeContext.empty();
        RecordingAgentToolDecorator decorator = new RecordingAgentToolDecorator(
                new StubTool("fast_tool", p -> Mono.just(ToolResultBlock.text("{}"))),
                false, MAPPER, CLOCK);

        decorator.callAsync(param(use("call_d3", "fast_tool"), rc)).block();

        assertThat(TrustContext.current(rc).invocations().get(0).durationMs())
                .as("非负即可（亚毫秒折 0 合法，慢机折 1 亦合法）")
                .isGreaterThanOrEqualTo(0L);
    }
}
