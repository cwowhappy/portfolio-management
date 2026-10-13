package com.portfolio.invest.infrastructure.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AllocationPlanWeightJpaRepository extends JpaRepository<AllocationPlanWeightJpaEntity, Long> {
    List<AllocationPlanWeightJpaEntity> findByPlanId(Long planId);

    /**
     * 整替权重行（save 内 delete+insert 新行）。
     * 不加 clearAutomatically（2026-10-12 B5 复核）：DELETE 前入上下文的旧权重行实体未被修改
     * （提交脏检查无 SQL），新权重为全新 insert 行，读回经全量 SELECT 且不与已删行 ID 冲突——
     * 无 AllocationPlan 式「批量 UPDATE 被同上下文 save 覆盖」混用。
     */
    @Modifying
    @Query("DELETE FROM AllocationPlanWeightJpaEntity w WHERE w.planId = :planId")
    void deleteByPlanId(@Param("planId") Long planId);
}
