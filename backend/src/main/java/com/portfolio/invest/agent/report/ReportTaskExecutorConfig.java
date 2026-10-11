package com.portfolio.invest.agent.report;

import com.portfolio.invest.agent.HarnessAgentFactory;
import com.portfolio.invest.config.InvestProperties;
import java.time.Clock;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 研报任务执行装配（MS-32 B9，设计规格 §3.2 执行护栏）：
 * <ul>
 *   <li><strong>reportTaskExecutor</strong>：core=max=pool-size（invest.report.execution.pool-size，
 *       默认 2）、queueCapacity=0（SynchronousQueue 语义）+ AbortPolicy——满即拒，编排器
 *       {@code submit} 捕获 RejectedExecutionException 同步落任务 FAILED(AGENT_ERROR, 队列已满)；
 *       不等待停机（waitForTasksToCompleteOnShutdown=false）：in-flight 任务随停机中断，
 *       由 B11 启动恢复 Runner（PENDING/RUNNING→FAILED，可重试）收口。注意：本 Executor bean
 *       的存在使 Boot 的 applicationTaskExecutor 自动装配回退（Boot 4 条件仍为
 *       {@code @ConditionalOnMissingBean(Executor.class)}），WebMvc 异步经
 *       {@code AsyncSupportConfigurer} 按名查 applicationTaskExecutor 不得而回落 MVC 默认
 *       SimpleAsyncTaskExecutor——SSE 功能不受损（boot-webmvc 4.0.3 javap 核实按名查取）。</li>
 *   <li><strong>reportAgentRunnerFactory</strong>：per-task runner 装配真实现
 *       （{@link HarnessReportAgentRunner} 包 buildReportAgent 变体 + 单轮超时档）；
 *       {@link HarnessAgentFactory} 缺席（DEEPSEEK_API_KEY 未配置，条件 bean）经
 *       ObjectProvider 延迟到 per-task 构造抛 AGENT_ERROR——上下文无 key 仍可启动。</li>
 *   <li><strong>reportWorkflowClock</strong>：编排器时钟（UTC；elapsed/状态迁移/终态时间戳）。</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class ReportTaskExecutorConfig {

    @Bean(name = "reportTaskExecutor")
    public ThreadPoolTaskExecutor reportTaskExecutor(InvestProperties props) {
        int poolSize = props.getReport().getExecution().poolSize();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("report-task-");
        executor.setDaemon(true);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }

    @Bean
    public ReportAgentRunnerFactory reportAgentRunnerFactory(
            ObjectProvider<HarnessAgentFactory> agentFactory, InvestProperties props) {
        return (userId, usageHook) -> new HarnessReportAgentRunner(
                agentFactory.getIfAvailable(), props.getReport().getExecution().roundTimeout(),
                userId, usageHook);
    }

    @Bean
    public Clock reportWorkflowClock() {
        return Clock.systemUTC();
    }
}
