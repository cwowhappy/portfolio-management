package com.portfolio.invest.agent.report;

/**
 * 研报 Agent 每任务装配缝（MS-32 B9）：编排器对每任务 new {@link ReportUsageHook} 后经本缝
 * 换取 per-task {@link ReportAgentRunner}——真实现 {@link HarnessReportAgentRunner}（包
 * buildReportAgent 变体 + 单轮超时），测试/BDD 注入 fake 剧本回放。生产 bean 装配见
 * {@code ReportTaskExecutorConfig}（DEEPSEEK_API_KEY 缺席时 per-task 构造抛
 * AGENT_ERROR，任务 fail-closed）。
 */
@FunctionalInterface
public interface ReportAgentRunnerFactory {

    /** 装配 per-task runner：usageHook 由编排器创建并随 agent 挂载（整任务 token 累计读数口）。 */
    ReportAgentRunner create(Long userId, ReportUsageHook usageHook);
}
