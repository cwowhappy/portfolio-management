package com.portfolio.invest.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** backend 首个调度设施（feishu-messaging P1 引入）：仅原则预警巡检一个 cron 任务。 */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
