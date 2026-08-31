package com.portfolio.invest.infrastructure.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AllocationPlanWeightJpaRepository extends JpaRepository<AllocationPlanWeightJpaEntity, Long> {
    List<AllocationPlanWeightJpaEntity> findByPlanId(Long planId);

    @Modifying
    @Query("DELETE FROM AllocationPlanWeightJpaEntity w WHERE w.planId = :planId")
    void deleteByPlanId(@Param("planId") Long planId);
}
