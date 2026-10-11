package com.portfolio.invest.agent.report;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PostSummaryEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatUsage;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MS-32 B8 研报用量钩子（per-task 实例非 bean）：POST_REASONING/POST_SUMMARY 各 Msg 的
 * input+output 逐次累计（B0 探针 3 实证事件源）；usage 缺席（null）安全跳过；
 * POST_CALL 不累计（探针 3：usage 只在 REASONING/SUMMARY 事件携带）。
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

    // ———— 脚手架 ————

    private static String nextId() {
        return "m-" + SEQ.incrementAndGet();
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
