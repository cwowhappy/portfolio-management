package com.portfolio.invest.agent.report;

import com.portfolio.invest.agent.HarnessAgentFactory;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import java.time.Duration;
import java.util.Objects;

/**
 * 研报 Agent runner 真实现（MS-32 B9）：构造期经 {@link HarnessAgentFactory#buildReportAgent}
 * 构建 per-task agent（同 agent 实例跨轮续会话——stateStore 按 (userId, sessionId=report-&lt;taskId&gt;)
 * 寻址，消息历史服务端持久），{@link #call} 内单轮 block(roundTimeout)（沿
 * {@code HarnessAgentInvoker.ask} 范式）。per-task 实例非 Spring bean，经
 * {@link ReportAgentRunnerFactory} 装配。
 *
 * <p>超时语义：reactor {@code block(Duration)} 超时抛 {@code IllegalStateException}
 * （"Timeout on blocking read"）→ 转 {@link ReportWorkflowException}(TIMEOUT)；工厂缺席
 * （DEEPSEEK_API_KEY 未配置）构造即抛 AGENT_ERROR——均由编排器 fail-closed 收敛任务 FAILED。
 */
public final class HarnessReportAgentRunner implements ReportAgentRunner {

    private final HarnessAgent agent;
    private final Duration roundTimeout;

    public HarnessReportAgentRunner(HarnessAgentFactory factory, Duration roundTimeout,
            Long userId, ReportUsageHook usageHook) {
        if (factory == null) {
            throw new ReportWorkflowException(ReportWorkflowException.AGENT_ERROR,
                    "DEEPSEEK_API_KEY 未配置，研报 Agent 不可用");
        }
        this.agent = factory.buildReportAgent(
                Objects.requireNonNull(userId, "userId 不能为空"),
                Objects.requireNonNull(usageHook, "usageHook 不能为空"));
        this.roundTimeout = Objects.requireNonNull(roundTimeout, "roundTimeout 不能为空");
    }

    @Override
    public String call(RuntimeContext rc, String userPrompt) {
        Msg reply;
        try {
            reply = agent.call(
                            Msg.builder().role(MsgRole.USER).textContent(userPrompt).build(), rc)
                    .block(roundTimeout);
        } catch (IllegalStateException e) {
            throw new ReportWorkflowException(ReportWorkflowException.TIMEOUT,
                    "研报单轮调用超时（上限 " + roundTimeout.toSeconds() + "s）：" + e.getMessage());
        }
        return reply == null ? null : reply.getTextContent();
    }
}
