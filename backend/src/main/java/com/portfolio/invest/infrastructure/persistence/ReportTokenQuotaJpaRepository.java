package com.portfolio.invest.infrastructure.persistence;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReportTokenQuotaJpaRepository extends JpaRepository<ReportTokenQuotaJpaEntity, ReportTokenQuotaJpaEntity.Pk> {

    Optional<ReportTokenQuotaJpaEntity> findByUserIdAndQuotaDate(Long userId, LocalDate quotaDate);

    /**
     * 结算 upsert（设计规格 §3.9）：当日无行则插入 delta，有行则原子累加（EXCLUDED 携带增量）——
     * 同用户同日并发结算不丢增量。该查询为 {@link Modifying} 原生语句，要求调用方处于
     * 活动事务中（事务边界见 application 层）；clear/flush 自动清理一级缓存，防同事务
     * 后续读到结算前的旧值（照 AllocationPlanJpaRepository.deactivateAllByUserId 用法）。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "INSERT INTO report_token_quota (user_id, quota_date, tokens_used) "
            + "VALUES (:userId, :quotaDate, :delta) "
            + "ON CONFLICT (user_id, quota_date) "
            + "DO UPDATE SET tokens_used = report_token_quota.tokens_used + EXCLUDED.tokens_used",
            nativeQuery = true)
    void settle(@Param("userId") Long userId, @Param("quotaDate") LocalDate quotaDate,
                @Param("delta") long delta);
}
