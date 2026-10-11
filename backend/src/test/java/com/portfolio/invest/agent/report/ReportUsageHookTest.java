package com.portfolio.invest.agent.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.agent.trust.ToolInvocation;
import com.portfolio.invest.agent.trust.TrustContext;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PostSummaryEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatUsage;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MS-32 B8 研报用量钩子（per-task 实例非 bean）：POST_REASONING/POST_SUMMARY 各 Msg 的
 * input+output 逐次累计（B0 探针 3 实证事件源）；usage 缺席（null）安全跳过；
 * POST_CALL 不累计（探针 3：usage 只在 REASONING/SUMMARY 事件携带）。
 * Ruling-21 修复③扩展：POST_CALL 真值池轮末快照（drainTruthInvocations 排空口径）——
 * 异步线程记录、跨轮独立、未接线降级三钉。
 */
class ReportUsageHookTest {

    private static final AtomicLong SEQ = new AtomicLong();

    /** 事件构造要求的非空 Agent 替身（TrustAgentHookTest 同款：hook 不解引用其行为面，全方法桩实现）。 */
    static final Agent STUB_AGENT = new Agent() {
        @Override
        public String getAgentId() {
            return "stub";
        }

        @Override
        public String getName() {
            return "stub";
        }

        @Override
        public void interrupt() {
        }

        @Override
        public void interrupt(Msg msg) {
        }

        @Override
        public reactor.core.publisher.Mono<Msg> call(List<Msg> messages) {
            return reactor.core.publisher.Mono.empty();
        }

        @Override
        public reactor.core.publisher.Mono<Msg> call(List<Msg> messages, Class<?> outputFormat) {
            return reactor.core.publisher.Mono.empty();
        }

        @Override
        public reactor.core.publisher.Mono<Msg> call(List<Msg> messages,
                com.fasterxml.jackson.databind.JsonNode outputFormat) {
            return reactor.core.publisher.Mono.empty();
        }

        @Override
        public reactor.core.publisher.Flux<io.agentscope.core.agent.Event> stream(
                List<Msg> messages, io.agentscope.core.agent.StreamOptions options) {
            return reactor.core.publisher.Flux.empty();
        }

        @Override
        public reactor.core.publisher.Flux<io.agentscope.core.agent.Event> stream(
                List<Msg> messages, io.agentscope.core.agent.StreamOptions options, Class<?> outputFormat) {
            return reactor.core.publisher.Flux.empty();
        }

        @Override
        public reactor.core.publisher.Flux<io.agentscope.core.agent.Event> stream(
                List<Msg> messages, io.agentscope.core.agent.StreamOptions options,
                com.fasterxml.jackson.databind.JsonNode outputFormat) {
            return reactor.core.publisher.Flux.empty();
        }

        @Override
        public reactor.core.publisher.Mono<Void> observe(Msg message) {
            return reactor.core.publisher.Mono.empty();
        }

        @Override
        public reactor.core.publisher.Mono<Void> observe(List<Msg> messages) {
            return reactor.core.publisher.Mono.empty();
        }
    };

    @DisplayName("POST_REASONING 携带 usage → input+output 计入累计")
    @Test
    void givenUsageOnReasoningMsg_whenPostReasoning_thenInputPlusOutputAccumulated() {
        ReportUsageHook hook = new ReportUsageHook();

        hook.onEvent(postReasoning(new ChatUsage(100, 50, 0.01))).block();

        assertThat(hook.totalTokens()).isEqualTo(150);
    }

    @DisplayName("POST_SUMMARY 携带 usage → 同口径计入（maxIters 耗尽收尾路径）")
    @Test
    void givenUsageOnSummaryMsg_whenPostSummary_thenInputPlusOutputAccumulated() {
        ReportUsageHook hook = new ReportUsageHook();

        hook.onEvent(postSummary(new ChatUsage(30, 20, 0.01))).block();

        assertThat(hook.totalTokens()).isEqualTo(50);
    }

    @DisplayName("Msg 无 usage（模型缺报）→ 安全跳过，累计保持 0")
    @Test
    void givenMsgWithoutUsage_whenPostReasoning_thenSkippedAndTotalStaysZero() {
        ReportUsageHook hook = new ReportUsageHook();
        Msg noUsage = Msg.builder().id(nextId()).role(MsgRole.ASSISTANT).textContent("推理正文").build();

        hook.onEvent(new PostReasoningEvent(STUB_AGENT, "deepseek-chat", null, noUsage)).block();

        assertThat(hook.totalTokens()).isZero();
    }

    @DisplayName("多轮多事件累计：REASONING×2 + SUMMARY×1 逐次求和（跨轮不重置）")
    @Test
    void givenMultipleModelCalls_whenEventsFire_thenSummedAcrossRounds() {
        ReportUsageHook hook = new ReportUsageHook();

        hook.onEvent(postReasoning(new ChatUsage(1000, 200, 0.01))).block();
        hook.onEvent(postReasoning(new ChatUsage(500, 80, 0.01))).block();
        hook.onEvent(postSummary(new ChatUsage(300, 20, 0.01))).block();

        assertThat(hook.totalTokens()).isEqualTo(2100);
    }

    @DisplayName("POST_CALL 携带 usage 也不累计（探针 3：usage 只在 REASONING/SUMMARY 事件携带）")
    @Test
    void givenUsageOnFinalMessage_whenPostCall_thenNotAccumulated() {
        ReportUsageHook hook = new ReportUsageHook();

        hook.onEvent(new PostCallEvent(STUB_AGENT, assistantMsg(new ChatUsage(100, 50, 0.01)))).block();

        assertThat(hook.totalTokens()).isZero();
    }

    // ———— Ruling-21 修复③：POST_CALL 真值池轮末快照通道 ————

    @DisplayName("修复③主通道：真值记录在异步线程的派生 rc 池 → POST_CALL 快照 → 排空可得（跨线程可见）")
    @Test
    void givenTruthRecordedOnAnotherThread_whenPostCallFires_thenDrained()
            throws Exception {
        ReportUsageHook hook = new ReportUsageHook();
        RuntimeContext derived = RuntimeContext.builder()
                .userId("1").sessionId("report-1").build();
        // 工具装饰器生产路径：reactor 工具线程记录进派生 rc 池
        CountDownLatch recorded = new CountDownLatch(1);
        Thread toolThread = new Thread(() -> {
            seedPool(derived, truth("get_quote", "现价1520.33元"));
            recorded.countDown();
        });
        toolThread.start();
        recorded.await(5, TimeUnit.SECONDS);

        hook.setRuntimeContext(derived);
        hook.onEvent(new PostCallEvent(STUB_AGENT, endMsg())).block();

        List<ToolInvocation> drained = hook.drainTruthInvocations();
        assertThat(drained).hasSize(1);
        assertThat(drained.get(0).toolName()).isEqualTo("get_quote");
        assertThat(drained.get(0).resultText()).contains("1520.33");
        assertThat(hook.drainTruthInvocations()).as("排空语义：再次排空为空（防跨轮重复累计）").isEmpty();
    }

    @DisplayName("修复③跨轮：第二轮 POST_CALL 只含新真值（派生池被 fresh 覆盖后重记录），排空逐轮独立")
    @Test
    void givenSecondRoundRecords_whenPostCallFiresAgain_thenOnlyNewTruthDrained() {
        ReportUsageHook hook = new ReportUsageHook();
        RuntimeContext derived = RuntimeContext.builder()
                .userId("1").sessionId("report-1").build();
        hook.setRuntimeContext(derived);

        seedPool(derived, truth("get_quote", "现价1520.33元"));
        hook.onEvent(new PostCallEvent(STUB_AGENT, endMsg())).block();
        assertThat(hook.drainTruthInvocations()).hasSize(1);

        seedPool(derived, truth("get_valuation", "市盈率35.40"));
        hook.onEvent(new PostCallEvent(STUB_AGENT, endMsg())).block();
        List<ToolInvocation> second = hook.drainTruthInvocations();
        assertThat(second).hasSize(1);
        assertThat(second.get(0).toolName()).isEqualTo("get_valuation");
    }

    @DisplayName("修复③降级：runtimeContext 未接线（null）→ POST_CALL 无操作，排空恒空不炸")
    @Test
    void givenRuntimeContextAbsent_whenPostCallFires_thenNoOp() {
        ReportUsageHook hook = new ReportUsageHook();

        hook.onEvent(new PostCallEvent(STUB_AGENT, endMsg())).block();

        assertThat(hook.drainTruthInvocations()).isEmpty();
        assertThat(hook.totalTokens()).isZero();
    }

    // ———— 脚手架 ————

    /** 派生 rc 池注入（fresh+record 包私有于 trust 包，report 包测试反射——沿 OrchestratorTest 同款）。 */
    private static void seedPool(RuntimeContext rc, ToolInvocation... invocations) {
        try {
            Method fresh = TrustContext.class.getDeclaredMethod("fresh", RuntimeContext.class);
            fresh.setAccessible(true);
            fresh.invoke(null, rc);
            Method record = TrustContext.class.getDeclaredMethod("record", ToolInvocation.class);
            record.setAccessible(true);
            Object pool = TrustContext.current(rc);
            for (ToolInvocation invocation : invocations) {
                record.invoke(pool, invocation);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("真值池反射注入失败", e);
        }
        TrustContext.reset(); // 模拟 trust hook POST_CALL 收尾清 ThreadLocal——rc 池不受影响（读取时点安全）
    }

    private static ToolInvocation truth(String toolName, String resultText) {
        return new ToolInvocation(toolName, java.util.Map.of("code", "600519"), resultText,
                List.of(), "2026-10-11 09:00:00", ToolInvocation.AsOfKind.DATA, false, false, 120L);
    }

    private static String nextId() {
        return "m-" + SEQ.incrementAndGet();
    }

    private static Msg endMsg() {
        return Msg.builder().id(nextId()).role(MsgRole.ASSISTANT).textContent("轮收尾").build();
    }

    private static Msg assistantMsg(ChatUsage usage) {
        return Msg.builder().id(nextId()).role(MsgRole.ASSISTANT).textContent("正文").usage(usage).build();
    }

    private static PostReasoningEvent postReasoning(ChatUsage usage) {
        return new PostReasoningEvent(STUB_AGENT, "deepseek-chat", null, assistantMsg(usage));
    }

    private static PostSummaryEvent postSummary(ChatUsage usage) {
        return new PostSummaryEvent(STUB_AGENT, "deepseek-chat", null, assistantMsg(usage));
    }
}
