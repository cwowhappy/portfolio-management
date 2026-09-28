package com.portfolio.invest.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 调度设施（feishu-messaging P1 引入）：@EnableScheduling 全局一次，cron 任务以 @Scheduled
 *  挂在各自 @Service 上（原则预警 18:33、证伪日终扫描 18:43 错峰，P3-T5）。 */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
