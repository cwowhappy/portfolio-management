package com.portfolio.invest.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ResearchReportJpaRepository extends JpaRepository<ResearchReportJpaEntity, Long> {

    List<ResearchReportJpaEntity> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    Optional<ResearchReportJpaEntity> findByTaskId(Long taskId);

    /**
     * 确认条件迁移（MS-32 B11，§3.10 并发恰一）：状态行 CAS——命中 1 / 非 PENDING 或
     * 并发已迁移 0。clear/flush 自动清理一级缓存，防同事务后续读到迁移前旧值
     * （照 AllocationPlanJpaRepository.deactivateAllByUserId 用法）。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE research_report SET status = 'CONFIRMED', confirmed_at = :confirmedAt, "
            + "updated_at = :updatedAt WHERE id = :id AND status = 'PENDING_CONFIRM'",
            nativeQuery = true)
    int confirmIfPending(@Param("id") Long id, @Param("confirmedAt") Instant confirmedAt,
                         @Param("updatedAt") Instant updatedAt);

    /** 拒绝条件迁移（reason 留痕）：语义同 {@link #confirmIfPending}——命中 1 / 未命中 0。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE research_report SET status = 'REJECTED', rejected_reason = :reason, "
            + "updated_at = :updatedAt WHERE id = :id AND status = 'PENDING_CONFIRM'",
            nativeQuery = true)
    int rejectIfPending(@Param("id") Long id, @Param("reason") String reason,
                        @Param("updatedAt") Instant updatedAt);
}
