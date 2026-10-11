package com.portfolio.invest.agent.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.invest.agent.HarnessAgentFactory;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * 研报 Agent runner 真实现单测（MS-32 B9）：调用范式沿 {@code HarnessAgentInvoker.ask} 先例
 * （block(roundTimeout) + getTextContent），单轮超时转 {@link ReportWorkflowException}(TIMEOUT)。
 * mock 三件套照抄 HarnessAgentInvokerTest（工厂/agent/Msg 均可 mock，零 LLM）。
 */
class HarnessReportAgentRunnerTest {

    private final HarnessAgentFactory factory = mock(HarnessAgentFactory.class);
    private final HarnessAgent agent = mock(HarnessAgent.class);
    private final ReportUsageHook usageHook = new ReportUsageHook();

    @DisplayName("正常轮：agent 返回文本 Msg → call 透传文本（block 内封）")
    @Test
    void givenAgentReplyText_whenCall_thenTextPassedThrough() {
        when(factory.buildReportAgent(7L, usageHook)).thenReturn(agent);
        Msg reply = mock(Msg.class);
        when(reply.getTextContent()).thenReturn("收集阶段小结");
        when(agent.call(any(Msg.class), any(RuntimeContext.class))).thenReturn(Mono.just(reply));
        HarnessReportAgentRunner runner =
                new HarnessReportAgentRunner(factory, Duration.ofSeconds(240), 7L, usageHook);

        String text = runner.call(rc(), "请开始收集");

        assertThat(text).isEqualTo("收集阶段小结");
    }

    @DisplayName("超时轮：Mono 不发射 → block 超时抛 IllegalStateException → 转 ReportWorkflowException(TIMEOUT)")
    @Test
    void givenNeverEmittingAgent_whenCall_thenTimeoutWorkflowException() {
        when(factory.buildReportAgent(7L, usageHook)).thenReturn(agent);
        when(agent.call(any(Msg.class), any(RuntimeContext.class))).thenReturn(Mono.never());
        HarnessReportAgentRunner runner =
                new HarnessReportAgentRunner(factory, Duration.ofMillis(150), 7L, usageHook);

        assertThatThrownBy(() -> runner.call(rc(), "请开始收集"))
                .isInstanceOf(ReportWorkflowException.class)
                .extracting(e -> ((ReportWorkflowException) e).errorCode())
                .isEqualTo(ReportWorkflowException.TIMEOUT);
    }

    @DisplayName("无工厂（DEEPSEEK_API_KEY 未配置）：构造即抛 AGENT_ERROR（fail-closed，任务转 FAILED）")
    @Test
    void givenNullFactory_whenConstruct_thenAgentErrorWorkflowException() {
        assertThatThrownBy(() ->
                new HarnessReportAgentRunner(null, Duration.ofSeconds(240), 7L, usageHook))
                .isInstanceOf(ReportWorkflowException.class)
                .extracting(e -> ((ReportWorkflowException) e).errorCode())
                .isEqualTo(ReportWorkflowException.AGENT_ERROR);
    }

    @DisplayName("空回复轮：getTextContent 为 null → 原样返回 null（解析层按 FENCE_MISSING 分流）")
    @Test
    void givenNullTextReply_whenCall_thenReturnsNull() {
        when(factory.buildReportAgent(7L, usageHook)).thenReturn(agent);
        Msg reply = mock(Msg.class);
        when(reply.getTextContent()).thenReturn(null);
        when(agent.call(any(Msg.class), any(RuntimeContext.class))).thenReturn(Mono.just(reply));
        HarnessReportAgentRunner runner =
                new HarnessReportAgentRunner(factory, Duration.ofSeconds(240), 7L, usageHook);

        assertThat(runner.call(rc(), "请开始收集")).isNull();
    }

    private RuntimeContext rc() {
        return RuntimeContext.builder().userId("7").sessionId("report-1").build();
    }
}
