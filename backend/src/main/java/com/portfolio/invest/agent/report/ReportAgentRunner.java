package com.portfolio.invest.agent.report;

import io.agentscope.core.agent.RuntimeContext;

/**
 * 研报 Agent 单轮调用缝（MS-32 B9，设计规格 §3.2 调用范式）：封装一次 call 的
 * block(roundTimeout) 与超时转 {@link ReportWorkflowException}(TIMEOUT)，返回最终消息文本
 * （沿 {@code HarnessAgentInvoker.ask} 先例；null 文本原样透传——解析层按 FENCE_MISSING 分流）。
 *
 * <p>per-task 实例（非 Spring bean）：每任务经 {@link ReportAgentRunnerFactory} 装配
 * （agent 与 {@link ReportUsageMiddleware} 均随任务构建）；同 {@code rc}（sessionId=report-&lt;taskId&gt;）
 * 续会话多轮。B14 BDD/fake 测试以本接口注入剧本回放。
 */
public interface ReportAgentRunner {

    /** 单轮对话调用：返回 Agent 最终消息文本；超时/Agent 异常抛 {@link ReportWorkflowException}。 */
    String call(RuntimeContext rc, String userPrompt);
}
