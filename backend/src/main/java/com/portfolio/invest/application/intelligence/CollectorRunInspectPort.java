package com.portfolio.invest.application.intelligence;

import java.time.Instant;
import java.util.List;

/**
 * collector 运行记录巡读端口（D19 社融降级判定的取数侧）：infrastructure 实现
 * 以 JdbcTemplate <b>只读</b> collector Alembic 建的跨服务运维表 collector_task_run
 * （JOIN collector_task 按 task_code 过滤，trading_calendar 同款先例——运维表不
 * 入 backend Flyway，backend 侧绝不写入）。
 *
 * <p>语义口径（MS-22 探测报告 §4.2）：只回<b>终态</b>行（{@code finished_at IS NOT
 * NULL}——running 前置行未终态跳过）；status 值域来自 collector/model/run.py：
 * success / partial（两态必带 source_used，executor 回填）/ failed（AllSourcesFailed
 * 终行 source_used 为 NULL）/ skipped。
 */
public interface CollectorRunInspectPort {

    /**
     * 指定任务近 recentDays 天（含）的终态 run，按 started_at 升序。表不可读
     * （backend 独立部署 / collector 首刷前未建表）降级返回空列表——映射「无 run
     * 的月不参与判定」，冷启动不阻断巡检（照 TradingCalendarPort 降级先例）。
     */
    List<TaskRunSummary> runsOf(String taskCode, int recentDays);

    /** 终态 run 摘要：月度聚组判定消费的三字段（status / 命中源 / 起跑时刻）。 */
    record TaskRunSummary(String status, String sourceUsed, Instant startedAt) {
    }
}
