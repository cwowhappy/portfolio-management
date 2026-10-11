package com.portfolio.invest.domain.research;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 用户日 token 配额仓库端口（MS-32，设计规格 §3.9）：(userId, quotaDate) 定位的当日累计，
 * 读用于配额闸门校验（QUOTA_TASK_CAP），写走 settle upsert 累加（任务终态结算）。
 */
public interface ReportTokenQuotaRepository {

    Optional<ReportTokenQuota> findByUserIdAndDate(Long userId, LocalDate date);

    /**
     * 结算 upsert（设计规格 §3.9）：INSERT ... ON CONFLICT (user_id, quota_date)
     * DO UPDATE SET tokens_used = tokens_used + delta——并发结算不丢增量。
     * 原生 @Modifying 查询，要求调用方处于活动事务中（事务边界在 application 层）。
     */
    void settle(Long userId, LocalDate date, long delta);
}
