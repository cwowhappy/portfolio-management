package com.portfolio.invest.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 调度设施（feishu-messaging P1 引入）：@EnableScheduling 全局一次，cron 任务以 @Scheduled
 *  挂在各自 @Service 上（原则预警 18:33、证伪日终扫描 18:43 错峰，P3-T5）。
 *
 * <p>MS-30 B2 eval 副作用禁用双保险②（设计规格 §2.3）：eval 子进程以命令行参数
 * {@code --Eval_MODE=true} 触发本配置整体不注册——@EnableScheduling 失效即全部 @Scheduled
 * cron 在评测上下文静默缺席（双保险①为调度器 env 白名单，Task 6）。条件表达式沿
 * {@code HarnessAgentFactory} 类级 @ConditionalOnExpression 先例（@ConditionalOnProperty/
 * @Profile 全仓零使用，不引入新机制）；生产进程不设该参数，行为不变。 */
@Configuration
@ConditionalOnExpression("!'true'.equals('${Eval_MODE:}')")
@EnableScheduling
public class SchedulingConfig {}
